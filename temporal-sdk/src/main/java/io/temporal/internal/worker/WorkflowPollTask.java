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

package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkflowPollTask implements Poller.PollTask<PollWorkflowTaskQueueResponse> {
  private static final Logger log = LoggerFactory.getLogger(WorkflowPollTask.class);

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final String identity;
  private final String binaryChecksum;
  private final Scope metricsScope;

  WorkflowPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nullable String binaryChecksum,
      @Nonnull Scope metricsScope) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.identity = Objects.requireNonNull(identity);
    this.binaryChecksum = binaryChecksum;
    this.metricsScope = Objects.requireNonNull(metricsScope);
  }

  @Override
  public PollWorkflowTaskQueueResponse poll() {
    PollWorkflowTaskQueueRequest pollRequest =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setBinaryChecksum(binaryChecksum)
            .setIdentity(identity)
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build())
            .build();

    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }
    PollWorkflowTaskQueueResponse result;
    try {
      result =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .pollWorkflowTaskQueue(pollRequest);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE
          && e.getMessage().startsWith("UNAVAILABLE: Channel shutdown")) {
        return null;
      }
      throw e;
    }
    if (log.isTraceEnabled()) {
      log.trace(
          "poll request returned workflow task: workflowType="
              + result.getWorkflowType()
              + ", workflowExecution="
              + result.getWorkflowExecution()
              + ", startedEventId="
              + result.getStartedEventId()
              + ", previousStartedEventId="
              + result.getPreviousStartedEventId()
              + (result.getQuery() != null
                  ? ", queryType=" + result.getQuery().getQueryType()
                  : ""));
    }

    if (result == null || result.getTaskToken().isEmpty()) {
      metricsScope.counter(MetricsType.WORKFLOW_TASK_QUEUE_POLL_EMPTY_COUNTER).inc(1);
      return null;
    }
    metricsScope.counter(MetricsType.WORKFLOW_TASK_QUEUE_POLL_SUCCEED_COUNTER).inc(1);
    metricsScope
        .timer(MetricsType.WORKFLOW_TASK_SCHEDULE_TO_START_LATENCY)
        .record(ProtobufTimeUtils.toM3Duration(result.getStartedTime(), result.getScheduledTime()));
    return result;
  }
}
