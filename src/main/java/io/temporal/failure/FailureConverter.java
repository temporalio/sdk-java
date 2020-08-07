/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.failure;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.ActivityFailureInfo;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.ChildWorkflowExecutionFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.ResetWorkflowFailureInfo;
import io.temporal.api.failure.v1.ServerFailureInfo;
import io.temporal.api.failure.v1.TerminatedFailureInfo;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.client.ActivityCancelledException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.testing.SimulatedTimeoutFailure;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FailureConverter {

  private static final Logger log = LoggerFactory.getLogger(FailureConverter.class);

  public static final String JAVA_SDK = "JavaSDK";
  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework level code.
   */
  private static final ImmutableSet<String> CUTOFF_METHOD_NAMES =
      ImmutableSet.of(
          "io.temporal.internal.worker.POJOActivityImplementationFactory$POJOActivityImplementation.execute",
          "io.temporal.internal.sync.POJOWorkflowTaskHandler$POJOWorkflowImplementation.execute");

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  public static RuntimeException failureToException(Failure failure, DataConverter dataConverter) {
    if (failure == null) {
      return null;
    }
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

  private static RuntimeException failureToExceptionImpl(
      Failure failure, DataConverter dataConverter) {
    RuntimeException cause =
        failure.hasCause() ? failureToException(failure.getCause(), dataConverter) : null;
    switch (failure.getFailureInfoCase()) {
      case APPLICATION_FAILURE_INFO:
        {
          ApplicationFailureInfo info = failure.getApplicationFailureInfo();
          // Unwrap SimulatedTimeoutFailure
          if (failure.getSource().equals(JAVA_SDK)
              && info.getType().equals(SimulatedTimeoutFailure.class.getName())
              && cause != null) {
            return cause;
          }
          Optional<Payloads> details =
              info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
          return ApplicationFailure.newFromValues(
              failure.getMessage(),
              info.getType(),
              info.getNonRetryable(),
              new EncodedValues(details, dataConverter),
              cause);
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
          return new ApplicationFailure(
              failure.getMessage(),
              "ResetWorkflow",
              false,
              new EncodedValues(details, dataConverter),
              cause);
        }
      case ACTIVITY_FAILURE_INFO:
        {
          ActivityFailureInfo info = failure.getActivityFailureInfo();
          return new ActivityFailure(
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
      case FAILUREINFO_NOT_SET:
      default:
        throw new IllegalArgumentException("Failure info not set");
    }
  }

  public static Failure exceptionToFailure(Throwable e) {
    e = CheckedExceptionWrapper.unwrap(e);
    return exceptionToFailureNoUnwrapping(e);
  }

  public static Failure exceptionToFailureNoUnwrapping(Throwable e) {
    String message;
    if (e instanceof TemporalFailure) {
      TemporalFailure tf = (TemporalFailure) e;
      if (tf.getFailure().isPresent()) {
        return tf.getFailure().get();
      }
      message = tf.getOriginalMessage();
    } else {
      message = e.getMessage() == null ? "" : e.getMessage();
    }
    String stackTrace = serializeStackTrace(e);
    Failure.Builder failure =
        Failure.newBuilder().setMessage(message).setSource(JAVA_SDK).setStackTrace(stackTrace);
    if (e.getCause() != null) {
      failure.setCause(exceptionToFailure(e.getCause()));
    }
    if (e instanceof ApplicationFailure) {
      ApplicationFailure ae = (ApplicationFailure) e;
      ApplicationFailureInfo.Builder info =
          ApplicationFailureInfo.newBuilder()
              .setType(ae.getType())
              .setNonRetryable(ae.isNonRetryable());
      Optional<Payloads> details = ((EncodedValues) ae.getDetails()).toPayloads();
      if (details.isPresent()) {
        info.setDetails(details.get());
      }
      failure.setApplicationFailureInfo(info);
    } else if (e instanceof TimeoutFailure) {
      TimeoutFailure te = (TimeoutFailure) e;
      TimeoutFailureInfo.Builder info =
          TimeoutFailureInfo.newBuilder().setTimeoutType(te.getTimeoutType());
      Optional<Payloads> details = ((EncodedValues) te.getLastHeartbeatDetails()).toPayloads();
      if (details.isPresent()) {
        info.setLastHeartbeatDetails(details.get());
      }
      failure.setTimeoutFailureInfo(info);
    } else if (e instanceof CanceledFailure) {
      CanceledFailure ce = (CanceledFailure) e;
      CanceledFailureInfo.Builder info = CanceledFailureInfo.newBuilder();
      Optional<Payloads> details = ((EncodedValues) ce.getDetails()).toPayloads();
      if (details.isPresent()) {
        info.setDetails(details.get());
      }
      failure.setCanceledFailureInfo(info);
    } else if (e instanceof TerminatedFailure) {
      TerminatedFailure te = (TerminatedFailure) e;
      failure.setTerminatedFailureInfo(TerminatedFailureInfo.getDefaultInstance());
    } else if (e instanceof ServerFailure) {
      ServerFailure se = (ServerFailure) e;
      failure.setServerFailureInfo(
          ServerFailureInfo.newBuilder().setNonRetryable(se.isNonRetryable()));
    } else if (e instanceof ActivityFailure) {
      ActivityFailure ae = (ActivityFailure) e;
      ActivityFailureInfo.Builder info =
          ActivityFailureInfo.newBuilder()
              .setActivityId(ae.getActivityId() == null ? "" : ae.getActivityId())
              .setActivityType(ActivityType.newBuilder().setName(ae.getActivityType()))
              .setIdentity(ae.getIdentity())
              .setRetryState(ae.getRetryState())
              .setScheduledEventId(ae.getScheduledEventId())
              .setStartedEventId(ae.getStartedEventId());
      failure.setActivityFailureInfo(info);
    } else if (e instanceof ChildWorkflowFailure) {
      ChildWorkflowFailure ce = (ChildWorkflowFailure) e;
      ChildWorkflowExecutionFailureInfo.Builder info =
          ChildWorkflowExecutionFailureInfo.newBuilder()
              .setInitiatedEventId(ce.getInitiatedEventId())
              .setStartedEventId(ce.getStartedEventId())
              .setNamespace(ce.getNamespace() == null ? "" : ce.getNamespace())
              .setRetryState(ce.getRetryState())
              .setWorkflowType(WorkflowType.newBuilder().setName(ce.getWorkflowType()))
              .setWorkflowExecution(ce.getExecution());
      failure.setChildWorkflowExecutionFailureInfo(info);
    } else if (e instanceof ActivityCancelledException) {
      CanceledFailureInfo.Builder info = CanceledFailureInfo.newBuilder();
      failure.setCanceledFailureInfo(info);
    } else {
      ApplicationFailureInfo.Builder info =
          ApplicationFailureInfo.newBuilder()
              .setType(e.getClass().getName())
              .setNonRetryable(false);
      failure.setApplicationFailureInfo(info);
    }
    return failure.build();
  }

  /** Parses stack trace serialized using {@link #serializeStackTrace(Throwable)}. */
  public static StackTraceElement[] parseStackTrace(String stackTrace) {
    if (Strings.isNullOrEmpty(stackTrace)) {
      return new StackTraceElement[0];
    }
    try {
      @SuppressWarnings("StringSplitter")
      String[] lines = stackTrace.split("\r\n|\n");
      StackTraceElement[] result = new StackTraceElement[lines.length];
      for (int i = 0; i < lines.length; i++) {
        result[i] = parseStackTraceElement(lines[i]);
      }
      return result;
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
  private static StackTraceElement parseStackTraceElement(String line) {
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

  public static String serializeStackTrace(Throwable e) {
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
