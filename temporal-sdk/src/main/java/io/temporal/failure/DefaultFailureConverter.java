package io.temporal.failure;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.NexusHandlerErrorRetryBehavior;
import io.temporal.api.failure.v1.*;
import io.temporal.client.ActivityCanceledException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.FailureConverter;
import io.temporal.internal.activity.ActivityTaskHandlerImpl;
import io.temporal.internal.common.FailureUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.sync.POJOWorkflowImplementationFactory;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link FailureConverter} that implements the default cross-language-compatible conversion
 * algorithm.
 */
public final class DefaultFailureConverter implements FailureConverter {

  private static final Logger log = LoggerFactory.getLogger(DefaultFailureConverter.class);

  private static final String JAVA_SDK = "JavaSDK";

  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework-level code.
   */
  private static final ImmutableSet<String> CUTOFF_METHOD_NAMES =
      ImmutableSet.<String>builder()
          .addAll(ActivityTaskHandlerImpl.ACTIVITY_HANDLER_STACKTRACE_CUTOFF)
          .addAll(POJOWorkflowImplementationFactory.WORKFLOW_HANDLER_STACKTRACE_CUTOFF)
          .build();

  /** Used to parse a stack trace line. */
  private static final Pattern TRACE_ELEMENT_PATTERN =
      Pattern.compile(
          "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)");

  @Override
  @Nonnull
  public RuntimeException failureToException(
      @Nonnull Failure failure, @Nonnull DataConverter dataConverter) {
    Preconditions.checkNotNull(failure, "failure");
    Preconditions.checkNotNull(dataConverter, "dataConverter");
    RuntimeException result = failureToExceptionImpl(failure, dataConverter);
    if (result instanceof TemporalFailure) {
      ((TemporalFailure) result).setFailure(failure);
    }
    if (failure.getSource().equals(JAVA_SDK) && !failure.getStackTrace().isEmpty()) {
      StackTraceElement[] stackTrace = parseStackTrace(failure.getStackTrace());
      result.setStackTrace(stackTrace);
    }
    return result;
  }

  @SuppressWarnings("deprecation") // Continue to check operation id for history compatibility
  private RuntimeException failureToExceptionImpl(Failure failure, DataConverter dataConverter) {
    Exception cause =
        failure.hasCause() ? failureToException(failure.getCause(), dataConverter) : null;
    switch (failure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo info = failure.getApplicationFailureInfo();
          Optional<Payloads> details =
              info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
          return ApplicationFailure.newBuilder()
              .setMessage(failure.getMessage())
              .setType(info.getType())
              .setNonRetryable(info.getNonRetryable())
              .setDetails(new EncodedValues(details, dataConverter))
              .setCause(cause)
              .setNextRetryDelay(
                  info.hasNextRetryDelay()
                      ? ProtobufTimeUtils.toJavaDuration(info.getNextRetryDelay())
                      : null)
              .setCategory(FailureUtils.categoryFromProto(info.getCategory()))
              .build();
        }
      case TIMEOUT_FAILURE_INFO:
        {
          TimeoutFailureInfo info = failure.getTimeoutFailureInfo();
          Optional<Payloads> lastHeartbeatDetails =
              info.hasLastHeartbeatDetails()
                  ? Optional.of(info.getLastHeartbeatDetails())
                  : Optional.empty();
          TimeoutFailure tf =
              new TimeoutFailure(
                  failure.getMessage(),
                  new EncodedValues(lastHeartbeatDetails, dataConverter),
                  info.getTimeoutType(),
                  cause);
          tf.setStackTrace(new StackTraceElement[0]);
          return tf;
        }
      case CANCELED_FAILURE_INFO:
        {
          CanceledFailureInfo info = failure.getCanceledFailureInfo();
          Optional<Payloads> details =
              info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
          return new CanceledFailure(
              failure.getMessage(), new EncodedValues(details, dataConverter), cause);
        }
      case TERMINATED_FAILURE_INFO:
        return new TerminatedFailure(failure.getMessage(), cause);
      case SERVER_FAILURE_INFO:
        {
          ServerFailureInfo info = failure.getServerFailureInfo();
          return new ServerFailure(failure.getMessage(), info.getNonRetryable(), cause);
        }
      case RESET_WORKFLOW_FAILURE_INFO:
        {
          ResetWorkflowFailureInfo info = failure.getResetWorkflowFailureInfo();
          Optional<Payloads> details =
              info.hasLastHeartbeatDetails()
                  ? Optional.of(info.getLastHeartbeatDetails())
                  : Optional.empty();
          return ApplicationFailure.newBuilder()
              .setMessage(failure.getMessage())
              .setType("ResetWorkflow")
              .setDetails(new EncodedValues(details, dataConverter))
              .setCause(cause)
              .build();
        }
      case ACTIVITY_FAILURE_INFO:
        {
          ActivityFailureInfo info = failure.getActivityFailureInfo();
          return new ActivityFailure(
              failure.getMessage(),
              info.getScheduledEventId(),
              info.getStartedEventId(),
              info.getActivityType().getName(),
              info.getActivityId(),
              info.getRetryState(),
              info.getIdentity(),
              cause);
        }
      case CHILD_WORKFLOW_EXECUTION_FAILURE_INFO:
        {
          ChildWorkflowExecutionFailureInfo info = failure.getChildWorkflowExecutionFailureInfo();
          return new ChildWorkflowFailure(
              info.getInitiatedEventId(),
              info.getStartedEventId(),
              info.getWorkflowType().getName(),
              info.getWorkflowExecution(),
              info.getNamespace(),
              info.getRetryState(),
              cause);
        }
      case NEXUS_OPERATION_EXECUTION_FAILURE_INFO:
        {
          NexusOperationFailureInfo info = failure.getNexusOperationExecutionFailureInfo();
          return new NexusOperationFailure(
              failure.getMessage(),
              info.getScheduledEventId(),
              info.getEndpoint(),
              info.getService(),
              info.getOperation(),
              info.getOperationToken().isEmpty() ? info.getOperationId() : info.getOperationToken(),
              cause);
        }
      case NEXUS_HANDLER_FAILURE_INFO:
        {
          NexusHandlerFailureInfo info = failure.getNexusHandlerFailureInfo();
          HandlerException.RetryBehavior retryBehavior = HandlerException.RetryBehavior.UNSPECIFIED;
          switch (info.getRetryBehavior()) {
            case NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_RETRYABLE:
              retryBehavior = HandlerException.RetryBehavior.RETRYABLE;
              break;
            case NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_NON_RETRYABLE:
              retryBehavior = HandlerException.RetryBehavior.NON_RETRYABLE;
              break;
          }
          return new HandlerException(info.getType(), cause, retryBehavior);
        }
      case FAILUREINFO_NOT_SET:
      default:
        // All unknown types are considered to be retryable ApplicationError.
        return ApplicationFailure.newBuilder()
            .setMessage(failure.getMessage())
            .setType("")
            .setDetails(new EncodedValues(Optional.empty(), dataConverter))
            .setCause(cause)
            .build();
    }
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(
      @Nonnull Throwable throwable, @Nonnull DataConverter dataConverter) {
    Preconditions.checkNotNull(dataConverter, "dataConverter");
    Preconditions.checkNotNull(throwable, "throwable");
    Throwable ex = throwable;
    while (ex != null) {
      if (ex instanceof TemporalFailure) {
        ((TemporalFailure) ex).setDataConverter(dataConverter);
      }
      ex = ex.getCause();
    }
    return this.exceptionToFailure(throwable);
  }

  @Nonnull
  @SuppressWarnings("deprecation") // Continue to check operation id for history compatibility
  private Failure exceptionToFailure(Throwable throwable) {
    if (throwable instanceof CheckedExceptionWrapper) {
      return exceptionToFailure(throwable.getCause());
    }
    String message;
    if (throwable instanceof TemporalFailure) {
      TemporalFailure tf = (TemporalFailure) throwable;
      if (tf.getFailure().isPresent()) {
        return tf.getFailure().get();
      }
      message = tf.getOriginalMessage();
    } else {
      message = throwable.getMessage() == null ? "" : throwable.getMessage();
    }
    String stackTrace = serializeStackTrace(throwable);
    Failure.Builder failure = Failure.newBuilder().setSource(JAVA_SDK);
    failure.setMessage(message).setStackTrace(stackTrace);
    if (throwable.getCause() != null) {
      failure.setCause(exceptionToFailure(throwable.getCause()));
    }
    if (throwable instanceof ApplicationFailure) {
      ApplicationFailure ae = (ApplicationFailure) throwable;
      ApplicationFailureInfo.Builder info =
          ApplicationFailureInfo.newBuilder()
              .setType(ae.getType())
              .setNonRetryable(ae.isNonRetryable());
      Optional<Payloads> details = ((EncodedValues) ae.getDetails()).toPayloads();
      if (details.isPresent()) {
        info.setDetails(details.get());
      }
      if (ae.getNextRetryDelay() != null) {
        info.setNextRetryDelay(ProtobufTimeUtils.toProtoDuration(ae.getNextRetryDelay()));
      }
      if (ae.getCategory() != null) {
        info.setCategory(FailureUtils.categoryToProto(ae.getCategory()));
      }
      failure.setApplicationFailureInfo(info);
    } else if (throwable instanceof TimeoutFailure) {
      TimeoutFailure te = (TimeoutFailure) throwable;
      TimeoutFailureInfo.Builder info =
          TimeoutFailureInfo.newBuilder().setTimeoutType(te.getTimeoutType());
      Optional<Payloads> details = ((EncodedValues) te.getLastHeartbeatDetails()).toPayloads();
      if (details.isPresent()) {
        info.setLastHeartbeatDetails(details.get());
      }
      failure.setTimeoutFailureInfo(info);
    } else if (throwable instanceof CanceledFailure) {
      CanceledFailure ce = (CanceledFailure) throwable;
      CanceledFailureInfo.Builder info = CanceledFailureInfo.newBuilder();
      Optional<Payloads> details = ((EncodedValues) ce.getDetails()).toPayloads();
      if (details.isPresent()) {
        info.setDetails(details.get());
      }
      failure.setCanceledFailureInfo(info);
    } else if (throwable instanceof TerminatedFailure) {
      TerminatedFailure te = (TerminatedFailure) throwable;
      failure.setTerminatedFailureInfo(TerminatedFailureInfo.getDefaultInstance());
    } else if (throwable instanceof ServerFailure) {
      ServerFailure se = (ServerFailure) throwable;
      failure.setServerFailureInfo(
          ServerFailureInfo.newBuilder().setNonRetryable(se.isNonRetryable()));
    } else if (throwable instanceof ActivityFailure) {
      ActivityFailure ae = (ActivityFailure) throwable;
      ActivityFailureInfo.Builder info =
          ActivityFailureInfo.newBuilder()
              .setActivityId(ae.getActivityId() == null ? "" : ae.getActivityId())
              .setActivityType(ActivityType.newBuilder().setName(ae.getActivityType()))
              .setIdentity(ae.getIdentity())
              .setRetryState(ae.getRetryState())
              .setScheduledEventId(ae.getScheduledEventId())
              .setStartedEventId(ae.getStartedEventId());
      failure.setActivityFailureInfo(info);
    } else if (throwable instanceof ChildWorkflowFailure) {
      ChildWorkflowFailure ce = (ChildWorkflowFailure) throwable;
      ChildWorkflowExecutionFailureInfo.Builder info =
          ChildWorkflowExecutionFailureInfo.newBuilder()
              .setInitiatedEventId(ce.getInitiatedEventId())
              .setStartedEventId(ce.getStartedEventId())
              .setNamespace(ce.getNamespace() == null ? "" : ce.getNamespace())
              .setRetryState(ce.getRetryState())
              .setWorkflowType(WorkflowType.newBuilder().setName(ce.getWorkflowType()))
              .setWorkflowExecution(ce.getExecution());
      failure.setChildWorkflowExecutionFailureInfo(info);
    } else if (throwable instanceof ActivityCanceledException) {
      CanceledFailureInfo.Builder info = CanceledFailureInfo.newBuilder();
      failure.setCanceledFailureInfo(info);
    } else if (throwable instanceof NexusOperationFailure) {
      NexusOperationFailure no = (NexusOperationFailure) throwable;
      NexusOperationFailureInfo.Builder op =
          NexusOperationFailureInfo.newBuilder()
              .setScheduledEventId(no.getScheduledEventId())
              .setEndpoint(no.getEndpoint())
              .setService(no.getService())
              .setOperation(no.getOperation())
              .setOperationId(no.getOperationToken())
              .setOperationToken(no.getOperationToken());
      failure.setNexusOperationExecutionFailureInfo(op);
    } else if (throwable instanceof HandlerException) {
      HandlerException he = (HandlerException) throwable;
      NexusHandlerErrorRetryBehavior retryBehavior =
          NexusHandlerErrorRetryBehavior.NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_UNSPECIFIED;
      switch (he.getRetryBehavior()) {
        case RETRYABLE:
          retryBehavior =
              NexusHandlerErrorRetryBehavior.NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_RETRYABLE;
          break;
        case NON_RETRYABLE:
          retryBehavior =
              NexusHandlerErrorRetryBehavior.NEXUS_HANDLER_ERROR_RETRY_BEHAVIOR_NON_RETRYABLE;
          break;
      }
      NexusHandlerFailureInfo.Builder info =
          NexusHandlerFailureInfo.newBuilder()
              .setType(he.getRawErrorType())
              .setRetryBehavior(retryBehavior);
      failure.setNexusHandlerFailureInfo(info);
    } else {
      ApplicationFailureInfo.Builder info =
          ApplicationFailureInfo.newBuilder()
              .setType(throwable.getClass().getName())
              .setNonRetryable(false)
              .setCategory(
                  io.temporal.api.enums.v1.ApplicationErrorCategory
                      .APPLICATION_ERROR_CATEGORY_UNSPECIFIED);
      failure.setApplicationFailureInfo(info);
    }
    return failure.build();
  }

  /** Parses stack trace serialized using {@link #serializeStackTrace(Throwable)}. */
  private StackTraceElement[] parseStackTrace(String stackTrace) {
    if (Strings.isNullOrEmpty(stackTrace)) {
      return new StackTraceElement[0];
    }
    try {
      @SuppressWarnings("StringSplitter")
      String[] lines = stackTrace.split("\r\n|\n");
      ArrayList<StackTraceElement> result = new ArrayList<>(lines.length);
      for (int i = 0; i < lines.length; i++) {
        StackTraceElement elem = parseStackTraceElement(lines[i]);
        if (elem != null) {
          result.add(elem);
        }
      }
      return result.toArray(new StackTraceElement[result.size()]);
    } catch (Exception e) {
      if (log.isWarnEnabled()) {
        log.warn("Failed to parse stack trace: " + stackTrace);
      }
      return new StackTraceElement[0];
    }
  }

  /**
   * See {@link StackTraceElement#toString()} for input specification.
   *
   * @param line line of stack trace.
   * @return StackTraceElement that contains data from that line.
   */
  private StackTraceElement parseStackTraceElement(String line) {
    Matcher matcher = TRACE_ELEMENT_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return null;
    }
    String declaringClass = matcher.group("className");
    String methodName = matcher.group("methodName");
    String fileName = matcher.group("fileName");
    int lineNumber = 0;
    String lns = matcher.group("lineNumber");
    if (lns != null && lns.length() > 0) {
      try {
        lineNumber = Integer.parseInt(matcher.group("lineNumber"));
      } catch (NumberFormatException e) {
      }
    }
    return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
  }

  private String serializeStackTrace(Throwable e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    StackTraceElement[] trace = e.getStackTrace();
    for (StackTraceElement element : trace) {
      pw.println(element);
      String fullMethodName = element.getClassName() + "." + element.getMethodName();
      if (CUTOFF_METHOD_NAMES.contains(fullMethodName)) {
        break;
      }
    }
    return sw.toString();
  }
}
