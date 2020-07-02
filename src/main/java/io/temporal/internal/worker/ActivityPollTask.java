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

import com.google.protobuf.DoubleValue;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.taskqueue.v1.TaskQueue;
import io.temporal.taskqueue.v1.TaskQueueMetadata;
import io.temporal.workflowservice.v1.PollForActivityTaskRequest;
import io.temporal.workflowservice.v1.PollForActivityTaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ActivityPollTask implements Poller.PollTask<PollForActivityTaskResponse> {

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private static final Logger log = LoggerFactory.getLogger(ActivityPollTask.class);
  private final double taskQueueActivitiesPerSecond;

  public ActivityPollTask(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      SingleWorkerOptions options,
      double taskQueueActivitiesPerSecond) {

    this.service = service;
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.options = options;
    this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
  }

  @Override
  public PollForActivityTaskResponse poll() {
    options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_COUNTER).inc(1);
    Stopwatch sw = options.getMetricsScope().timer(MetricsType.ACTIVITY_POLL_LATENCY).start();

    PollForActivityTaskRequest.Builder pollRequest =
        PollForActivityTaskRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(options.getIdentity())
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));
    if (taskQueueActivitiesPerSecond > 0) {
      pollRequest.setTaskQueueMetadata(
          TaskQueueMetadata.newBuilder()
              .setMaxTasksPerSecond(
                  DoubleValue.newBuilder().setValue(taskQueueActivitiesPerSecond).build())
              .build());
    }

    if (taskQueueActivitiesPerSecond > 0) {
      pollRequest.setTaskQueueMetadata(
          TaskQueueMetadata.newBuilder()
              .setMaxTasksPerSecond(
                  DoubleValue.newBuilder().setValue(taskQueueActivitiesPerSecond).build())
              .build());
    }

    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }
    PollForActivityTaskResponse result;
    try {
      result = service.blockingStub().pollForActivityTask(pollRequest.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE
          && e.getMessage().startsWith("UNAVAILABLE: Channel shutdown")) {
        return null;
      }
      if (e.getStatus().getCode() == Status.Code.INTERNAL
          || e.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
        options
            .getMetricsScope()
            .counter(MetricsType.ACTIVITY_POLL_TRANSIENT_FAILED_COUNTER)
            .inc(1);
      } else {
        options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_FAILED_COUNTER).inc(1);
      }
      throw e;
    }

    if (result == null || result.getTaskToken().isEmpty()) {
      options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
      return null;
    }

    options.getMetricsScope().counter(MetricsType.ACTIVITY_POLL_SUCCEED_COUNTER).inc(1);
    options
        .getMetricsScope()
        .timer(MetricsType.ACTIVITY_SCHEDULED_TO_START_LATENCY)
        .record(
            Duration.ofNanos(
                result.getStartedTimestamp() - result.getScheduledTimestampOfThisAttempt()));
    sw.stop();
    return result;
  }
}
