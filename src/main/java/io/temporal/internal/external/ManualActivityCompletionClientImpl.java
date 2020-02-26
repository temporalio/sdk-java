/*
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

package io.temporal.internal.external;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.RecordActivityTaskHeartbeatRequest;
import io.temporal.RecordActivityTaskHeartbeatResponse;
import io.temporal.RespondActivityTaskCanceledByIDRequest;
import io.temporal.RespondActivityTaskCanceledRequest;
import io.temporal.RespondActivityTaskCompletedByIDRequest;
import io.temporal.RespondActivityTaskCompletedRequest;
import io.temporal.RespondActivityTaskFailedByIDRequest;
import io.temporal.RespondActivityTaskFailedRequest;
import io.temporal.WorkflowExecution;
import io.temporal.client.ActivityCancelledException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.converter.DataConverter;
import io.temporal.internal.common.Retryer;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: service call retries
class ManualActivityCompletionClientImpl extends ManualActivityCompletionClient {

  private static final Logger log =
      LoggerFactory.getLogger(ManualActivityCompletionClientImpl.class);

  private final GrpcWorkflowServiceFactory service;

  private final ByteString taskToken;

  private final DataConverter dataConverter;
  private final String domain;
  private final WorkflowExecution execution;
  private final String activityId;
  private final Scope metricsScope;

  ManualActivityCompletionClientImpl(
      GrpcWorkflowServiceFactory service,
      ByteString taskToken,
      DataConverter dataConverter,
      Scope metricsScope) {
    this.service = service;
    this.taskToken = taskToken;
    this.dataConverter = dataConverter;
    this.domain = null;
    this.execution = null;
    this.activityId = null;
    this.metricsScope = metricsScope;
  }

  ManualActivityCompletionClientImpl(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution execution,
      String activityId,
      DataConverter dataConverter,
      Scope metricsScope) {
    this.service = service;
    this.taskToken = null;
    this.domain = domain;
    this.execution = execution;
    this.activityId = activityId;
    this.dataConverter = dataConverter;
    this.metricsScope = metricsScope;
  }

  @Override
  public void complete(Object result) {
    if (taskToken != null) {
      byte[] convertedResult = dataConverter.toData(result);
      RespondActivityTaskCompletedRequest request =
          RespondActivityTaskCompletedRequest.newBuilder()
              .setResult(ByteString.copyFrom(convertedResult))
              .setTaskToken(taskToken)
              .build();
      try {
        Retryer.retry(
            Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () -> service.blockingStub().respondActivityTaskCompleted(request));
        metricsScope.counter(MetricsType.ACTIVITY_TASK_COMPLETED_COUNTER).inc(1);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          throw new ActivityNotExistsException(e);
        } else {
          throw new ActivityCompletionFailureException(e);
        }
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      byte[] convertedResult = dataConverter.toData(result);
      RespondActivityTaskCompletedByIDRequest request =
          RespondActivityTaskCompletedByIDRequest.newBuilder()
              .setActivityID(activityId)
              .setResult(ByteString.copyFrom(convertedResult))
              .setDomain(domain)
              .setWorkflowID(execution.getWorkflowId())
              .setRunID(execution.getRunId())
              .build();
      try {
        service.blockingStub().respondActivityTaskCompletedByID(request);
        metricsScope.counter(MetricsType.ACTIVITY_TASK_COMPLETED_BY_ID_COUNTER).inc(1);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          throw new ActivityNotExistsException(e);
        } else {
          throw new ActivityCompletionFailureException(activityId, e);
        }
      }
    }
  }

  @Override
  public void fail(Throwable failure) {
    if (failure == null) {
      throw new IllegalArgumentException("null failure");
    }
    // When converting failures reason is class name, details are serialized exception.
    if (taskToken != null) {
      RespondActivityTaskFailedRequest request =
          RespondActivityTaskFailedRequest.newBuilder()
              .setReason(failure.getClass().getName())
              .setDetails(ByteString.copyFrom(dataConverter.toData(failure)))
              .setTaskToken(taskToken)
              .build();
      try {
        Retryer.retry(
            Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () -> service.blockingStub().respondActivityTaskFailed(request));
        metricsScope.counter(MetricsType.ACTIVITY_TASK_FAILED_COUNTER).inc(1);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          throw new ActivityNotExistsException(e);
        } else {
          throw new ActivityCompletionFailureException(e);
        }
      }
    } else {
      RespondActivityTaskFailedByIDRequest request =
          RespondActivityTaskFailedByIDRequest.newBuilder()
              .setReason(failure.getClass().getName())
              .setDetails(ByteString.copyFrom(dataConverter.toData(failure)))
              .setDomain(domain)
              .setWorkflowID(execution.getWorkflowId())
              .setRunID(execution.getRunId())
              .build();
      try {
        Retryer.retry(
            Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS,
            () -> service.blockingStub().respondActivityTaskFailedByID(request));
        metricsScope.counter(MetricsType.ACTIVITY_TASK_FAILED_BY_ID_COUNTER).inc(1);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          throw new ActivityNotExistsException(e);
        } else {
          throw new ActivityCompletionFailureException(activityId, e);
        }
      }
    }
  }

  @Override
  public void recordHeartbeat(Object details) throws CancellationException {
    if (taskToken != null) {
      RecordActivityTaskHeartbeatRequest request =
          RecordActivityTaskHeartbeatRequest.newBuilder()
              .setDetails(ByteString.copyFrom(dataConverter.toData(details)))
              .setTaskToken(taskToken)
              .build();
      RecordActivityTaskHeartbeatResponse status = null;
      try {
        status = service.blockingStub().recordActivityTaskHeartbeat(request);
        if (status.getCancelRequested()) {
          throw new ActivityCancelledException();
        }
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          throw new ActivityNotExistsException(e);
        } else {
          throw new ActivityCompletionFailureException(e);
        }
      }
    } else {
      throw new UnsupportedOperationException(
          "Heartbeating by id is not implemented by Temporal service yet.");
    }
  }

  @Override
  public void reportCancellation(Object details) {
    if (taskToken != null) {
      RespondActivityTaskCanceledRequest request =
          RespondActivityTaskCanceledRequest.newBuilder()
              .setDetails(ByteString.copyFrom(dataConverter.toData(details)))
              .setTaskToken(taskToken)
              .build();
      try {
        service.blockingStub().respondActivityTaskCanceled(request);
        metricsScope.counter(MetricsType.ACTIVITY_TASK_CANCELED_COUNTER).inc(1);
      } catch (StatusRuntimeException e) {
        // There is nothing that can be done at this point.
        // so let's just ignore.
        log.info("reportCancellation", e);
      }
    } else {
      RespondActivityTaskCanceledByIDRequest request =
          RespondActivityTaskCanceledByIDRequest.newBuilder()
              .setDetails(ByteString.copyFrom(dataConverter.toData(details)))
              .setDomain(domain)
              .setWorkflowID(execution.getWorkflowId())
              .setRunID(execution.getRunId())
              .build();
      try {
        service.blockingStub().respondActivityTaskCanceledByID(request);
        metricsScope.counter(MetricsType.ACTIVITY_TASK_CANCELED_BY_ID_COUNTER).inc(1);
      } catch (StatusRuntimeException e) {
        // There is nothing that can be done at this point.
        // so let's just ignore.
        log.info("reportCancellation", e);
      }
    }
  }
}
