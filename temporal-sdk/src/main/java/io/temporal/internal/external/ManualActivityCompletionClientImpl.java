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

package io.temporal.internal.external;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

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
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.client.ActivityClientHelper;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: service call retries
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
  }

  @Override
  public void complete(Object result) {
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
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompleted(request.build()));
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
        service
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
            .respondActivityTaskCompletedById(request.build());
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void fail(Throwable exception) {
    if (exception == null) {
      throw new IllegalArgumentException("null exception");
    }
    if (exception instanceof TemporalFailure) {
      ((TemporalFailure) exception).setDataConverter(dataConverter);
    }
    // When converting failures reason is class name, details are serialized exception.
    if (taskToken != null) {
      RespondActivityTaskFailedRequest request =
          RespondActivityTaskFailedRequest.newBuilder()
              .setFailure(FailureConverter.exceptionToFailure(exception))
              .setNamespace(namespace)
              .setTaskToken(ByteString.copyFrom(taskToken))
              .build();
      try {
        GrpcRetryer.retry(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskFailed(request));
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
              .setFailure(FailureConverter.exceptionToFailure(exception))
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(execution.getRunId())
              .setActivityId(activityId)
              .build();
      try {
        GrpcRetryer.retry(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskFailedById(request));
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void recordHeartbeat(Object details) throws CanceledFailure {
    if (taskToken != null) {
      try {
        ActivityClientHelper.sendHeartbeatRequest(
            dataConverter, identity, metricsScope, namespace, service, taskToken, details);
      } catch (Exception e) {
        if (e instanceof ActivityCanceledException) {
          throw e;
        }
        processException(e);
      }
    } else {
      try {
        ActivityClientHelper.recordActivityTaskHeartbeatById(
            activityId, dataConverter, execution, metricsScope, namespace, service, details);
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void reportCancellation(Object details) {
    Optional<Payloads> convertedDetails = dataConverter.toPayloads(details);
    if (taskToken != null) {
      RespondActivityTaskCanceledRequest.Builder request =
          RespondActivityTaskCanceledRequest.newBuilder()
              .setNamespace(namespace)
              .setTaskToken(ByteString.copyFrom(taskToken));
      if (convertedDetails.isPresent()) {
        request.setDetails(convertedDetails.get());
      }
      try {
        GrpcRetryer.retry(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCanceled(request.build()));
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
      if (convertedDetails.isPresent()) {
        request.setDetails(convertedDetails.get());
      }
      try {
        GrpcRetryer.retry(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCanceledById(request.build()));
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
