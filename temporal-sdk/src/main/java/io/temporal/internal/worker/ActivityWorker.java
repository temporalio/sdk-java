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
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.FailureWrapperException;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.internal.worker.activity.ActivityWorkerHelper;
import io.temporal.serviceclient.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

public final class ActivityWorker implements SuspendableWorker {

  private static final String POLL_THREAD_NAME_PREFIX = "Activity Poller taskQueue=";

  private SuspendableWorker poller = new NoopSuspendableWorker();
  private final ActivityTaskHandler handler;
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final double taskQueueActivitiesPerSecond;

  public ActivityWorker(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      double taskQueueActivitiesPerSecond,
      SingleWorkerOptions options,
      ActivityTaskHandler handler) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
    this.handler = handler;

    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  POLL_THREAD_NAME_PREFIX
                      + "\""
                      + taskQueue
                      + "\", namespace=\""
                      + namespace
                      + "\"")
              .build();
    }
    this.options = SingleWorkerOptions.newBuilder(options).setPollerOptions(pollerOptions).build();
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      poller =
          new Poller<>(
              options.getIdentity(),
              new ActivityPollTask(
                  service, namespace, taskQueue, options, taskQueueActivitiesPerSecond),
              new PollTaskExecutor<>(namespace, taskQueue, options, new TaskHandlerImpl(handler)),
              options.getPollerOptions(),
              options.getMetricsScope());
      poller.start();
      options.getMetricsScope().counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  @Override
  public boolean isStarted() {
    return poller.isStarted();
  }

  @Override
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated();
  }

  @Override
  public void shutdown() {
    poller.shutdown();
  }

  @Override
  public void shutdownNow() {
    poller.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    poller.awaitTermination(timeout, unit);
  }

  @Override
  public void suspendPolling() {
    poller.suspendPolling();
  }

  @Override
  public void resumePolling() {
    poller.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<ActivityTask> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(ActivityTask task) throws Exception {
      PollActivityTaskQueueResponse r = task.getResponse();
      Scope metricsScope =
          options
              .getMetricsScope()
              .tagged(
                  ImmutableMap.of(
                      MetricsTag.ACTIVITY_TYPE,
                      r.getActivityType().getName(),
                      MetricsTag.WORKFLOW_TYPE,
                      r.getWorkflowType().getName()));
      ActivityTaskHandler.Result response = null;
      try {
        metricsScope
            .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
            .record(
                ProtobufTimeUtils.toM3Duration(
                    r.getStartedTime(), r.getCurrentAttemptScheduledTime()));

        // The following tags are for logging.
        MDC.put(LoggerTag.ACTIVITY_ID, r.getActivityId());
        MDC.put(LoggerTag.ACTIVITY_TYPE, r.getActivityType().getName());
        MDC.put(LoggerTag.WORKFLOW_ID, r.getWorkflowExecution().getWorkflowId());
        MDC.put(LoggerTag.RUN_ID, r.getWorkflowExecution().getRunId());

        if (r.hasHeader()) {
          ActivityWorkerHelper.deserializeAndPopulateContext(
              r.getHeader(), options.getContextPropagators());
        }

        Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_EXEC_LATENCY).start();
        try {
          response = handler.handle(task, metricsScope, false);
        } finally {
          sw.stop();
        }
        sendReply(r, response, metricsScope);

        Duration duration =
            ProtobufTimeUtils.toM3DurationSinceNow(r.getCurrentAttemptScheduledTime());
        metricsScope.timer(MetricsType.ACTIVITY_E2E_LATENCY).record(duration);

      } catch (FailureWrapperException e) {
        Failure failure = e.getFailure();
        if (failure.hasCanceledFailureInfo()) {
          CanceledFailureInfo info = failure.getCanceledFailureInfo();
          RespondActivityTaskCanceledRequest.Builder canceledRequest =
              RespondActivityTaskCanceledRequest.newBuilder()
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace);
          if (info.hasDetails()) {
            canceledRequest.setDetails(info.getDetails());
          }
          response =
              new Result(r.getActivityId(), null, null, canceledRequest.build(), null, false);
          sendReply(r, response, metricsScope);
        }
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.RUN_ID);
        // Apply completion handle if task has been completed synchronously or is async and manual
        // completion hasn't been requested.
        if (response != null && !response.isManualCompletion()) {
          task.getCompletionHandle().apply();
        }
      }
    }

    @Override
    public Throwable wrapFailure(ActivityTask t, Throwable failure) {
      PollActivityTaskQueueResponse response = t.getResponse();
      WorkflowExecution execution = response.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing activity response. WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + ", ActivityType="
              + response.getActivityType().getName()
              + ", ActivityId="
              + response.getActivityId(),
          failure);
    }

    private void sendReply(
        PollActivityTaskQueueResponse task,
        ActivityTaskHandler.Result response,
        Scope metricsScope) {
      RpcRetryOptions ro = response.getRequestRetryOptions();
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = RpcRetryOptions.newBuilder().buildWithDefaultsFrom(ro);
        RespondActivityTaskCompletedRequest request =
            taskCompleted
                .toBuilder()
                .setTaskToken(task.getTaskToken())
                .setIdentity(options.getIdentity())
                .setNamespace(namespace)
                .build();
        GrpcRetryer.retry(
            ro,
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompleted(request));
      } else {
        Result.TaskFailedResult taskFailed = response.getTaskFailed();

        if (taskFailed != null) {
          RespondActivityTaskFailedRequest request =
              taskFailed
                  .getTaskFailedRequest()
                  .toBuilder()
                  .setTaskToken(task.getTaskToken())
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace)
                  .build();
          ro = RpcRetryOptions.newBuilder().buildWithDefaultsFrom(ro);

          GrpcRetryer.retry(
              ro,
              () ->
                  service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                      .respondActivityTaskFailed(request));
        } else {
          RespondActivityTaskCanceledRequest taskCanceled = response.getTaskCanceled();
          if (taskCanceled != null) {
            RespondActivityTaskCanceledRequest request =
                taskCanceled
                    .toBuilder()
                    .setTaskToken(task.getTaskToken())
                    .setIdentity(options.getIdentity())
                    .setNamespace(namespace)
                    .build();
            ro = RpcRetryOptions.newBuilder().buildWithDefaultsFrom(ro);

            GrpcRetryer.retry(
                ro,
                () ->
                    service
                        .blockingStub()
                        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                        .respondActivityTaskCanceled(request));
          }
        }
      }
      // Manual activity completion
    }
  }
}
