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
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.failure.ActivityTaskFailureInfo;
import io.temporal.proto.failure.ApplicationFailureInfo;
import io.temporal.proto.failure.CanceledFailureInfo;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.failure.ServerFailureInfo;
import io.temporal.workflow.ActivityFailureException;
import java.util.Optional;

public class FailureConverter {

  static final String JAVA_SDK = "JavaSDK";

  public static Exception failureToException(Failure failure, DataConverter dataConverter) {
    if (failure == null) {
      return null;
    }
    Exception cause =
        failure.hasCause() ? failureToException(failure.getCause(), dataConverter) : null;
    switch (failure.getFailureInfoCase()) {
      case APPLICATIONFAILUREINFO:
        return new ApplicationException(failure, dataConverter, cause);
      case TIMEOUTFAILUREINFO:
        return new TimeoutException(failure, dataConverter, cause);
      case CANCELEDFAILUREINFO:
        return new CanceledException(failure, dataConverter, cause);
      case TERMINATEDFAILUREINFO:
        return new TerminatedException(failure, cause);
      case SERVERFAILUREINFO:
        return new ServerException(failure, cause);
      case RESETWORKFLOWFAILUREINFO:
        return new ResetWorkflowException(failure, dataConverter, cause);
      case ACTIVITYTASKFAILUREINFO:
        ActivityTaskFailureInfo info = failure.getActivityTaskFailureInfo();
        // TODO(maxim): Fix activity type and activityid arguments.
        return new ActivityFailureException(
            failure.getMessage(),
            info.getScheduledEventId(),
            ActivityType.newBuilder().setName("unknown").build(),
            "unknown",
            cause);
      case CHILDWORKFLOWEXECUTIONFAILUREINFO:
        return new ChildWorkflowException(failure, cause);
      case FAILUREINFO_NOT_SET:
      default:
        throw new IllegalArgumentException("Failure info not set");
    }
  }

  public static Failure exceptionToFailure(Throwable e) {
    e = CheckedExceptionWrapper.unwrap(e);
    if (e instanceof RemoteException) {
      RemoteException te = (RemoteException) e;
      return te.getFailure();
    } else {
      StringBuilder st = new StringBuilder();
      for (StackTraceElement traceElement : e.getStackTrace()) {
        st.append(traceElement);
        st.append("\n");
      }
      ApplicationFailureInfo.Builder failureInfo =
          ApplicationFailureInfo.newBuilder()
              .setType(e.getClass().getName())
              .setNonRetryable(false);
      return Failure.newBuilder()
          .setMessage(e.getMessage())
          .setSource(JAVA_SDK)
          .setStackTrace(st.toString())
          .setApplicationFailureInfo(failureInfo)
          .build();
    }
  }

  //  private static void setTemporalException(
  //      Failure.Builder result, RemoteException te, DataConverter dataConverter) {
  //    // TimeoutException contains cause in lastFailure
  //    if (te.getCause() != null && !(te instanceof TimeoutException)) {
  //      result.setCause(exceptionToFailure(te.getCause(), dataConverter));
  //    }
  //    if (te instanceof ApplicationException) {
  //      setApplicationFailure(result, (ApplicationException) te);
  //    } else if (te instanceof TimeoutException) {
  //      setTimeoutFailure(result, (TimeoutException) te, dataConverter);
  //    } else if (te instanceof ActivityException) {
  //      setActivityTaskFailure(result, (ActivityException) te);
  //    } else if (te instanceof ChildWorkflowException) {
  //      setChildWorkflowFailure(result, (ChildWorkflowException) te);
  //    } else if (te instanceof CanceledException) {
  //      setCanceledFailure(result, (CanceledException) te);
  //    } else if (te instanceof TerminatedException) {
  //      result.setTerminatedFailureInfo(TerminatedFailureInfo.getDefaultInstance());
  //    } else if (te instanceof ServerException) {
  //      setServerFailure(result, (ServerException) te);
  //    } else if (te instanceof ResetWorkflowException) {
  //      setResetWorkflowFailure(result, (ResetWorkflowException) te);
  //    } else {
  //      setApplicationFailureFromUnknownException(result, te);
  //    }
  //  }
  //
  //  private static void setApplicationFailure(Failure.Builder result, ApplicationException e) {
  //    ApplicationFailureInfo.Builder failureInfo =
  //        ApplicationFailureInfo.newBuilder()
  //            .setType(e.getType())
  //            .setNonRetryable(e.isNonRetryable());
  //    Optional<Payloads> details = e.getDetails();
  //    if (details.isPresent()) {
  //      failureInfo.setDetails(details.get());
  //    }
  //    result.setApplicationFailureInfo(failureInfo);
  //  }
  //
  //  private static void setTimeoutFailure(
  //      Failure.Builder result, TimeoutException e, DataConverter dataConverter) {
  //    TimeoutFailureInfo.Builder failureInfo =
  //        TimeoutFailureInfo.newBuilder().setTimeoutType(e.getTimeoutType());
  //    Optional<Payloads> details = e.getLastHeartbeatDetails();
  //    if (details.isPresent()) {
  //      failureInfo.setLastHeartbeatDetails(details.get());
  //    }
  //    if (e.getCause() != null) {
  //      failureInfo.setLastFailure(exceptionToFailure(e.getCause()));
  //    }
  //    result.setTimeoutFailureInfo(failureInfo);
  //  }
  //
  //  private static void setActivityTaskFailure(Failure.Builder result, ActivityException e) {
  //    ActivityTaskFailureInfo.Builder failureInfo =
  //        ActivityTaskFailureInfo.newBuilder()
  //            .setScheduledEventId(e.getScheduledEventId())
  //            .setStartedEventId(e.getStartedEventId())
  //            .setIdentity(e.getIdentity());
  //    result.setActivityTaskFailureInfo(failureInfo);
  //  }
  //
  //  private static void setChildWorkflowFailure(Failure.Builder result, ChildWorkflowException e)
  // {
  //    ChildWorkflowExecutionFailureInfo.Builder failureInfo =
  //        ChildWorkflowExecutionFailureInfo.newBuilder()
  //            .setNamespace(e.getNamespace())
  //            .setWorkflowExecution(e.getWorkflowExecution())
  //            .setWorkflowType(e.getWorkflowType())
  //            .setInitiatedEventId(e.getInitiatedEventId())
  //            .setStartedEventId(e.getStartedEventId());
  //    result.setChildWorkflowExecutionFailureInfo(failureInfo);
  //  }
  //
  //  private static void setResetWorkflowFailure(Failure.Builder result, ResetWorkflowException e)
  // {
  //    ResetWorkflowFailureInfo.Builder failureInfo = ResetWorkflowFailureInfo.newBuilder();
  //    Optional<Payloads> details = e.getLastHeartbeatDetails();
  //    if (details.isPresent()) {
  //      failureInfo.setLastHeartbeatDetails(details.get());
  //    }
  //    result.setResetWorkflowFailureInfo(failureInfo);
  //  }

  private static void setServerFailure(Failure.Builder result, ServerException e) {
    ServerFailureInfo.Builder failureInfo =
        ServerFailureInfo.newBuilder().setNonRetryable(e.isNonRetryable());
    result.setServerFailureInfo(failureInfo);
  }

  private static void setCanceledFailure(Failure.Builder result, CanceledException e) {
    CanceledFailureInfo.Builder failureInfo = CanceledFailureInfo.newBuilder();
    Optional<Payloads> details = e.getDetails();
    if (details.isPresent()) {
      failureInfo.setDetails(details.get());
    }
    result.setCanceledFailureInfo(failureInfo);
  }

  private static void setApplicationFailureFromUnknownException(
      Failure.Builder result, Throwable e) {
    ApplicationFailureInfo.Builder failureInfo =
        ApplicationFailureInfo.newBuilder().setType(e.getClass().getName()).setNonRetryable(true);
    result.setApplicationFailureInfo(failureInfo);
  }
}
