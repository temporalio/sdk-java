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

import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.DoubleValue;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.taskqueue.v1.TaskQueueMetadata;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ActivityPollTask implements Poller.PollTask<PollActivityTaskQueueResponse> {

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private static final Logger log = LoggerFactory.getLogger(ActivityPollTask.class);
  private final double taskQueueActivitiesPerSecond;
  private final Scope metricsScope;

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
    this.metricsScope = options.getMetricsScope();
    this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
  }

  @Override
  public PollActivityTaskQueueResponse poll() {
    PollActivityTaskQueueRequest.Builder pollRequest =
        PollActivityTaskQueueRequest.newBuilder()
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
    PollActivityTaskQueueResponse result;
    try {
      result =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .pollActivityTaskQueue(pollRequest.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE
          && e.getMessage().startsWith("UNAVAILABLE: Channel shutdown")) {
        return null;
      }
      throw e;
    }

    if (result == null || result.getTaskToken().isEmpty()) {
      metricsScope.counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
      return null;
    }
    metricsScope
        .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
        .record(
            ProtobufTimeUtils.ToM3Duration(
                result.getStartedTime(), result.getCurrentAttemptScheduledTime()));

    return result;
  }
}
