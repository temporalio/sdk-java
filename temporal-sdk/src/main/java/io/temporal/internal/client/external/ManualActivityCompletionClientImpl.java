/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.client.external;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.client.ActivityClientHelper;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ManualActivityCompletionClientImpl implements ManualActivityCompletionClient {

  private static final Logger log =
      LoggerFactory.getLogger(ManualActivityCompletionClientImpl.class);

  private final WorkflowServiceStubs service;
  private final WorkflowExecution execution;
  private final DataConverter dataConverter;
  private final String namespace;
  private final String identity;
  private final String activityId;
  private final Scope metricsScope;
  private final byte[] taskToken;
  private final GrpcRetryer.GrpcRetryerOptions replyGrpcRetryerOptions;

  ManualActivityCompletionClientImpl(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      byte[] taskToken,
      DataConverter dataConverter,
      Scope metricsScope) {
    this.service = service;
    this.execution = null;
    this.dataConverter = dataConverter;
    this.namespace = namespace;
    this.identity = identity;
    this.activityId = null;
    this.metricsScope = metricsScope;
    this.taskToken = taskToken;
    this.replyGrpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            null);
  }

  ManualActivityCompletionClientImpl(
      WorkflowServiceStubs service,
      String namespace,
      String identity,
      WorkflowExecution execution,
      String activityId,
      DataConverter dataConverter,
      Scope metricsScope) {
    this.service = service;
    this.taskToken = null;
    this.namespace = namespace;
    this.identity = identity;
    this.execution = execution;
    this.activityId = activityId;
    this.dataConverter = dataConverter;
    this.metricsScope = metricsScope;
    this.replyGrpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            null);
  }

  @Override
  public void complete(@Nullable Object result) {
    Optional<Payloads> payloads = dataConverter.toPayloads(result);
    if (taskToken != null) {
      RespondActivityTaskCompletedRequest.Builder request =
          RespondActivityTaskCompletedRequest.newBuilder()
              .setNamespace(namespace)
              .setIdentity(identity)
              .setTaskToken(ByteString.copyFrom(taskToken));
      payloads.ifPresent(request::setResult);
      try {
        GrpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompleted(request.build()),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        processException(e);
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      RespondActivityTaskCompletedByIdRequest.Builder request =
          RespondActivityTaskCompletedByIdRequest.newBuilder()
              .setActivityId(activityId)
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(execution.getRunId());
      payloads.ifPresent(request::setResult);
      try {
        GrpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompletedById(request.build()),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void fail(@Nonnull Throwable exception) {
    Preconditions.checkNotNull(exception, "null exception");
    // When converting failures reason is class name, details are serialized exception.
    if (taskToken != null) {
      RespondActivityTaskFailedRequest request =
          RespondActivityTaskFailedRequest.newBuilder()
              .setFailure(FailureConverter.exceptionToFailure(exception, dataConverter))
              .setNamespace(namespace)
              .setTaskToken(ByteString.copyFrom(taskToken))
              .build();
      try {
        GrpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskFailed(request),
            replyGrpcRetryerOptions);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
          throw new ActivityNotExistsException(e);
        }
        throw new ActivityCompletionFailureException(e);
      } catch (Exception e) {
        throw new ActivityCompletionFailureException(e);
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      RespondActivityTaskFailedByIdRequest request =
          RespondActivityTaskFailedByIdRequest.newBuilder()
              .setFailure(FailureConverter.exceptionToFailure(exception, dataConverter))
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(execution.getRunId())
              .setActivityId(activityId)
              .build();
      try {
        GrpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskFailedById(request),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void recordHeartbeat(@Nullable Object details) throws CanceledFailure {
    try {
      if (taskToken != null) {
        RecordActivityTaskHeartbeatResponse status =
            ActivityClientHelper.sendHeartbeatRequest(
                service, namespace, identity, taskToken, dataConverter, metricsScope, details);
        if (status.getCancelRequested()) {
          throw new ActivityCanceledException();
        }
      } else {
        RecordActivityTaskHeartbeatByIdResponse status =
            ActivityClientHelper.recordActivityTaskHeartbeatById(
                service,
                namespace,
                identity,
                execution,
                activityId,
                dataConverter,
                metricsScope,
                details);
        if (status.getCancelRequested()) {
          throw new ActivityCanceledException();
        }
      }
    } catch (Exception e) {
      processException(e);
    }
  }

  @Override
  public void reportCancellation(@Nullable Object details) {
    Optional<Payloads> convertedDetails = dataConverter.toPayloads(details);
    if (taskToken != null) {
      RespondActivityTaskCanceledRequest.Builder request =
          RespondActivityTaskCanceledRequest.newBuilder()
              .setNamespace(namespace)
              .setTaskToken(ByteString.copyFrom(taskToken));
      convertedDetails.ifPresent(request::setDetails);
      try {
        GrpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCanceled(request.build()),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        // There is nothing that can be done at this point.
        // so let's just ignore.
        log.info("reportCancellation", e);
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      RespondActivityTaskCanceledByIdRequest.Builder request =
          RespondActivityTaskCanceledByIdRequest.newBuilder()
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(OptionsUtils.safeGet(execution.getRunId()))
              .setActivityId(activityId);
      convertedDetails.ifPresent(request::setDetails);
      try {
        GrpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCanceledById(request.build()),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        // There is nothing that can be done at this point.
        // so let's just ignore.
        log.warn("reportCancellation", e);
      }
    }
  }

  private void processException(Exception e) {
    if (e instanceof StatusRuntimeException) {
      StatusRuntimeException sre = (StatusRuntimeException) e;
      if (sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new ActivityNotExistsException(activityId, sre);
      }
    }
    throw new ActivityCompletionFailureException(activityId, e);
  }
}
