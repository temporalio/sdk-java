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

package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkflowPollTask implements Poller.PollTask<WorkflowTask> {
  private static final Logger log = LoggerFactory.getLogger(WorkflowPollTask.class);

  private final String taskQueue;
  private final Semaphore workflowTaskExecutorSemaphore;
  private final Scope metricsScope;
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub serviceStub;
  private final PollWorkflowTaskQueueRequest pollRequest;

  public WorkflowPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull TaskQueueKind taskQueueKind,
      @Nonnull String identity,
      @Nullable String binaryChecksum,
      @Nonnull Semaphore workflowTaskExecutorSemaphore,
      @Nonnull Scope metricsScope) {
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.workflowTaskExecutorSemaphore = workflowTaskExecutorSemaphore;
    this.metricsScope = Objects.requireNonNull(metricsScope);
    this.serviceStub =
        Objects.requireNonNull(service)
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope);

    this.pollRequest =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setNamespace(Objects.requireNonNull(namespace))
            .setIdentity(Objects.requireNonNull(identity))
            .setBinaryChecksum(binaryChecksum)
            .setTaskQueue(
                TaskQueue.newBuilder()
                    .setName(taskQueue)
                    // For matching performance optimizations of Temporal Server it's important to
                    // know if the poll comes for a sticky or a normal queue. Because sticky queues
                    // have only 1 partition, no forwarding is needed.
                    .setKind(Objects.requireNonNull(taskQueueKind))
                    .build())
            .build();
  }

  @Override
  public WorkflowTask poll() {
    log.trace("poll request begin: {}", pollRequest);
    PollWorkflowTaskQueueResponse response;
    boolean isSuccessful = false;

    try {
      workflowTaskExecutorSemaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }

    try {
      response = serviceStub.pollWorkflowTaskQueue(pollRequest);
      if (log.isTraceEnabled()) {
        log.trace(
            "poll request returned workflow task: taskQueue={}, workflowType={}, workflowExecution={}, startedEventId={}, previousStartedEventId={}{}",
            taskQueue,
            response.getWorkflowType(),
            response.getWorkflowExecution(),
            response.getStartedEventId(),
            response.getPreviousStartedEventId(),
            response.hasQuery() ? ", queryType=" + response.getQuery().getQueryType() : "");
      }

      if (response == null || response.getTaskToken().isEmpty()) {
        metricsScope.counter(MetricsType.WORKFLOW_TASK_QUEUE_POLL_EMPTY_COUNTER).inc(1);
        return null;
      }
      metricsScope.counter(MetricsType.WORKFLOW_TASK_QUEUE_POLL_SUCCEED_COUNTER).inc(1);
      metricsScope
          .timer(MetricsType.WORKFLOW_TASK_SCHEDULE_TO_START_LATENCY)
          .record(
              ProtobufTimeUtils.toM3Duration(
                  response.getStartedTime(), response.getScheduledTime()));
      isSuccessful = true;
      return new WorkflowTask(response, workflowTaskExecutorSemaphore::release);
    } finally {
      if (!isSuccessful) workflowTaskExecutorSemaphore.release();
    }
  }
}
