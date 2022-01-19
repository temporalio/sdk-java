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

import com.google.protobuf.DoubleValue;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.taskqueue.v1.TaskQueueMetadata;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ActivityPollTask implements Poller.PollTask<ActivityTask> {
  private static final Logger log = LoggerFactory.getLogger(ActivityPollTask.class);

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final Scope metricsScope;
  private final String identity;
  private final double activitiesPerSecond;
  private final Semaphore pollSemaphore;

  public ActivityPollTask(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      Scope metricsScope,
      String identity,
      double activitiesPerSecond,
      int workerTaskSlots) {

    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.metricsScope = Objects.requireNonNull(metricsScope);
    this.identity = Objects.requireNonNull(identity);
    this.activitiesPerSecond = activitiesPerSecond;
    this.pollSemaphore = new Semaphore(workerTaskSlots);
  }

  @Override
  public ActivityTask poll() {
    PollActivityTaskQueueRequest.Builder pollRequest =
        PollActivityTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(identity)
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));
    if (activitiesPerSecond > 0) {
      pollRequest.setTaskQueueMetadata(
          TaskQueueMetadata.newBuilder()
              .setMaxTasksPerSecond(DoubleValue.newBuilder().setValue(activitiesPerSecond).build())
              .build());
    }

    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }
    PollActivityTaskQueueResponse response;
    boolean isSuccessful = false;

    try {
      pollSemaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }

    try {
      response =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .pollActivityTaskQueue(pollRequest.build());

      if (response == null || response.getTaskToken().isEmpty()) {
        metricsScope.counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
        return null;
      }
      metricsScope
          .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
          .record(
              ProtobufTimeUtils.toM3Duration(
                  response.getStartedTime(), response.getCurrentAttemptScheduledTime()));
      isSuccessful = true;
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE
          && e.getMessage().startsWith("UNAVAILABLE: Channel shutdown")) {
        return null;
      }
      throw e;
    } finally {
      if (!isSuccessful) pollSemaphore.release();
    }
    return new ActivityTask(response, pollSemaphore::release);
  }
}
