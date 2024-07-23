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
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.*;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkflowPollTask implements Poller.PollTask<WorkflowTask> {
  private static final Logger log = LoggerFactory.getLogger(WorkflowPollTask.class);

  private final TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier;
  private final StickyQueueBalancer stickyQueueBalancer;
  private final Scope metricsScope;
  private final Scope stickyMetricsScope;
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub serviceStub;
  private final PollWorkflowTaskQueueRequest pollRequest;
  private final PollWorkflowTaskQueueRequest stickyPollRequest;

  public WorkflowPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nullable String stickyTaskQueue,
      @Nonnull String identity,
      @Nullable String buildId,
      boolean useBuildIdForVersioning,
      @Nonnull TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier,
      @Nonnull StickyQueueBalancer stickyQueueBalancer,
      @Nonnull Scope workerMetricsScope,
      @Nonnull Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities) {
    this.slotSupplier = Objects.requireNonNull(slotSupplier);
    this.stickyQueueBalancer = Objects.requireNonNull(stickyQueueBalancer);
    this.metricsScope = Objects.requireNonNull(workerMetricsScope);
    this.stickyMetricsScope =
        workerMetricsScope.tagged(
            new ImmutableMap.Builder<String, String>(1)
                .put(MetricsTag.TASK_QUEUE, String.format("%s:%s", taskQueue, "sticky"))
                .build());
    this.serviceStub =
        Objects.requireNonNull(service)
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope);

    PollWorkflowTaskQueueRequest.Builder pollRequestBuilder =
        PollWorkflowTaskQueueRequest.newBuilder()
            .setNamespace(Objects.requireNonNull(namespace))
            .setIdentity(Objects.requireNonNull(identity));

    if (serverCapabilities.get().getBuildIdBasedVersioning()) {
      pollRequestBuilder.setWorkerVersionCapabilities(
          WorkerVersionCapabilities.newBuilder()
              .setBuildId(buildId)
              .setUseVersioning(useBuildIdForVersioning)
              .build());
    } else {
      pollRequestBuilder.setBinaryChecksum(buildId);
    }

    this.pollRequest =
        pollRequestBuilder
            .setTaskQueue(
                TaskQueue.newBuilder()
                    .setName(taskQueue)
                    // For matching performance optimizations of Temporal Server it's important to
                    // know if the poll comes for a sticky or a normal queue. Because sticky queues
                    // have only 1 partition, no forwarding is needed.
                    .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
                    .build())
            .build();

    this.stickyPollRequest =
        pollRequestBuilder
            .setTaskQueue(
                TaskQueue.newBuilder()
                    .setName(stickyTaskQueue)
                    .setKind(TaskQueueKind.TASK_QUEUE_KIND_STICKY)
                    .setNormalName(taskQueue)
                    .build())
            .build();
  }

  @Override
  public WorkflowTask poll() {
    boolean isSuccessful = false;
    SlotPermit permit;
    try {
      permit =
          slotSupplier.reserveSlot(
              new SlotReservationData(
                  pollRequest.getTaskQueue().getName(),
                  pollRequest.getIdentity(),
                  pollRequest.getWorkerVersionCapabilities().getBuildId()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      log.warn("Error while trying to reserve a slot for workflow task", e.getCause());
      return null;
    }

    TaskQueueKind taskQueueKind = stickyQueueBalancer.makePoll();
    boolean isSticky = TaskQueueKind.TASK_QUEUE_KIND_STICKY.equals(taskQueueKind);
    PollWorkflowTaskQueueRequest request = isSticky ? stickyPollRequest : pollRequest;
    Scope scope = isSticky ? stickyMetricsScope : metricsScope;

    log.trace("poll request begin: {}", request);
    try {
      PollWorkflowTaskQueueResponse response = doPoll(request, scope);
      if (response == null) {
        return null;
      }
      isSuccessful = true;
      stickyQueueBalancer.finishPoll(taskQueueKind, response.getBacklogCountHint());
      slotSupplier.markSlotUsed(new WorkflowSlotInfo(response, pollRequest), permit);
      return new WorkflowTask(response, (rr) -> slotSupplier.releaseSlot(rr, permit));
    } finally {
      if (!isSuccessful) {
        slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
        stickyQueueBalancer.finishPoll(taskQueueKind, 0);
      }
    }
  }

  @Nullable
  private PollWorkflowTaskQueueResponse doPoll(
      PollWorkflowTaskQueueRequest request, Scope metricsScope) {
    PollWorkflowTaskQueueResponse response = serviceStub.pollWorkflowTaskQueue(request);

    if (log.isTraceEnabled()) {
      log.trace(
          "poll request returned workflow task: taskQueue={}, workflowType={}, workflowExecution={}, startedEventId={}, previousStartedEventId={}{}",
          request.getTaskQueue().getName(),
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
            ProtobufTimeUtils.toM3Duration(response.getStartedTime(), response.getScheduledTime()));
    return response;
  }
}
