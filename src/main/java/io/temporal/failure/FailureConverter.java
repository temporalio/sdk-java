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
import java.util.Optional;

public class FailureConverter {

  public static final String JAVA_SDK = "JavaSDK";

  public static Exception failureToException(Failure failure, DataConverter dataConverter) {
    if (failure == null) {
      return null;
    }
    Exception result = failureToExceptionImpl(failure, dataConverter);
    if (result instanceof TemporalFailure) {
      ((TemporalFailure) result).setFailure(failure);
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
    if (e instanceof TemporalFailure) {
      TemporalFailure tf = (TemporalFailure) e;
      if (tf.getFailure().isPresent()) {
        return tf.getFailure().get();
      }
    }
    StringBuilder st = new StringBuilder();
    for (StackTraceElement traceElement : e.getStackTrace()) {
      st.append(traceElement);
      st.append("\n");
    }
    Failure.Builder failure =
        Failure.newBuilder()
            .setMessage(e.getMessage())
            .setSource(JAVA_SDK)
            .setStackTrace(st.toString());
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
              .setActivityId(ae.getActivityId())
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
              .setNamespace(ce.getNamespace())
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
}
