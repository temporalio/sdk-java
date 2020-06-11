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
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValue;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.failure.ActivityFailureInfo;
import io.temporal.proto.failure.ApplicationFailureInfo;
import io.temporal.proto.failure.CanceledFailureInfo;
import io.temporal.proto.failure.ChildWorkflowExecutionFailureInfo;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.failure.ResetWorkflowFailureInfo;
import io.temporal.proto.failure.ServerFailureInfo;
import io.temporal.proto.failure.TerminatedFailureInfo;
import io.temporal.proto.failure.TimeoutFailureInfo;
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
          "io.temporal.internal.sync.POJODecisionTaskHandler$POJOWorkflowImplementation.execute");

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.(?<methodName>.*))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  public static Exception failureToException(Failure failure, DataConverter dataConverter) {
    if (failure == null) {
      return null;
    }
    Exception result = failureToExceptionImpl(failure, dataConverter);
    if (result instanceof TemporalFailure) {
      ((TemporalFailure) result).setFailure(failure);
    }
    if (failure.getSource().equals(JAVA_SDK) && !failure.getStackTrace().isEmpty()) {
      StackTraceElement[] stackTrace = parseStackTrace(failure.getStackTrace());
      result.setStackTrace(stackTrace);
    }
    return result;
  }

  private static Exception failureToExceptionImpl(Failure failure, DataConverter dataConverter) {
    Exception cause =
        failure.hasCause() ? failureToException(failure.getCause(), dataConverter) : null;
    switch (failure.getFailureInfoCase()) {
      case APPLICATIONFAILUREINFO:
        {
          ApplicationFailureInfo info = failure.getApplicationFailureInfo();
          Optional<Payloads> details =
              info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
          return new ApplicationException(
              failure.getMessage(),
              info.getType(),
              new EncodedValue(details, dataConverter),
              info.getNonRetryable(),
              cause);
        }
      case TIMEOUTFAILUREINFO:
        {
          TimeoutFailureInfo info = failure.getTimeoutFailureInfo();
          Optional<Payloads> lastHeartbeatDetails =
              info.hasLastHeartbeatDetails()
                  ? Optional.of(info.getLastHeartbeatDetails())
                  : Optional.empty();
          return new TimeoutFailure(
              failure.getMessage(),
              new EncodedValue(lastHeartbeatDetails, dataConverter),
              info.getTimeoutType(),
              cause);
        }
      case CANCELEDFAILUREINFO:
        {
          CanceledFailureInfo info = failure.getCanceledFailureInfo();
          Optional<Payloads> details =
              info.hasDetails() ? Optional.of(info.getDetails()) : Optional.empty();
          return new CanceledException(
              failure.getMessage(), new EncodedValue(details, dataConverter), cause);
        }
      case TERMINATEDFAILUREINFO:
        return new TerminatedException(failure.getMessage(), cause);
      case SERVERFAILUREINFO:
        {
          ServerFailureInfo info = failure.getServerFailureInfo();
          return new ServerException(failure.getMessage(), info.getNonRetryable(), cause);
        }
      case RESETWORKFLOWFAILUREINFO:
        {
          ResetWorkflowFailureInfo info = failure.getResetWorkflowFailureInfo();
          Optional<Payloads> details =
              info.hasLastHeartbeatDetails()
                  ? Optional.of(info.getLastHeartbeatDetails())
                  : Optional.empty();
          return new ApplicationException(
              failure.getMessage(), "ResetWorkflow", details, false, cause);
        }
      case ACTIVITYFAILUREINFO:
        {
          ActivityFailureInfo info = failure.getActivityFailureInfo();
          return new ActivityException(
              info.getScheduledEventId(),
              info.getStartedEventId(),
              info.getActivityType().getName(),
              info.getActivityId(),
              info.getRetryStatus(),
              info.getIdentity(),
              cause);
        }
      case CHILDWORKFLOWEXECUTIONFAILUREINFO:
        {
          ChildWorkflowExecutionFailureInfo info = failure.getChildWorkflowExecutionFailureInfo();
          return new ChildWorkflowException(
              info.getInitiatedEventId(),
              info.getStartedEventId(),
              info.getWorkflowType().getName(),
              info.getWorkflowExecution(),
              info.getNamespace(),
              info.getRetryStatus(),
              cause);
        }
      case FAILUREINFO_NOT_SET:
      default:
        throw new IllegalArgumentException("Failure info not set");
    }
  }

  public static Failure exceptionToFailure(Throwable e, DataConverter dataConverter) {
    e = CheckedExceptionWrapper.unwrap(e);
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
      failure.setCause(exceptionToFailure(e.getCause(), dataConverter));
    }
    if (e instanceof ApplicationException) {
      ApplicationException ae = (ApplicationException) e;
      ApplicationFailureInfo.Builder info =
          ApplicationFailureInfo.newBuilder().setType(ae.getType());
      Object value = ae.getDetails().get(Object.class);
      Optional<Payloads> details = dataConverter.toData(value);
      if (details.isPresent()) {
        info.setDetails(details.get());
      }
      failure.setApplicationFailureInfo(info);
    } else if (e instanceof TimeoutFailure) {
      TimeoutFailure te = (TimeoutFailure) e;
      TimeoutFailureInfo.Builder info = TimeoutFailureInfo.newBuilder();
      Object value = te.getLastHeartbeatDetails().get(Object.class);
      Optional<Payloads> details = dataConverter.toData(value);
      if (details.isPresent()) {
        info.setLastHeartbeatDetails(details.get());
      }
      failure.setTimeoutFailureInfo(info);
    } else if (e instanceof CanceledException) {
      CanceledException ce = (CanceledException) e;
      CanceledFailureInfo.Builder info = CanceledFailureInfo.newBuilder();
      Object value = ce.getDetails().get(Object.class);
      Optional<Payloads> details = dataConverter.toData(value);
      if (details.isPresent()) {
        info.setDetails(details.get());
      }
      failure.setCanceledFailureInfo(info);
    } else if (e instanceof TerminatedException) {
      TerminatedException te = (TerminatedException) e;
      failure.setTerminatedFailureInfo(TerminatedFailureInfo.getDefaultInstance());
    } else if (e instanceof ServerException) {
      ServerException se = (ServerException) e;
      failure.setServerFailureInfo(
          ServerFailureInfo.newBuilder().setNonRetryable(se.isNonRetryable()));
    } else if (e instanceof ActivityException) {
      ActivityException ae = (ActivityException) e;
      ActivityFailureInfo.Builder info =
          ActivityFailureInfo.newBuilder()
              .setActivityId(ae.getActivityId() == null ? "" : ae.getActivityId())
              .setActivityType(ActivityType.newBuilder().setName(ae.getActivityType()))
              .setIdentity(ae.getIdentity())
              .setRetryStatus(ae.getRetryStatus())
              .setScheduledEventId(ae.getScheduledEventId())
              .setStartedEventId(ae.getStartedEventId());
      failure.setActivityFailureInfo(info);
    } else if (e instanceof ChildWorkflowException) {
      ChildWorkflowException ce = (ChildWorkflowException) e;
      ChildWorkflowExecutionFailureInfo.Builder info =
          ChildWorkflowExecutionFailureInfo.newBuilder()
              .setInitiatedEventId(ce.getInitiatedEventId())
              .setStartedEventId(ce.getStartedEventId())
              .setNamespace(ce.getNamespace() == null ? "" : ce.getNamespace())
              .setRetryStatus(ce.getRetryStatus())
              .setWorkflowType(WorkflowType.newBuilder().setName(ce.getWorkflowType()))
              .setWorkflowExecution(ce.getExecution());
      failure.setChildWorkflowExecutionFailureInfo(info);
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
