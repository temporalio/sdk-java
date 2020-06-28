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

import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.v1.Payload;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.failure.v1.CanceledFailureInfo;
import io.temporal.failure.v1.Failure;
import io.temporal.internal.common.GrpcRetryer;
import io.temporal.internal.common.RpcRetryOptions;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.FailureWrapperException;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflowservice.v1.PollForActivityTaskResponse;
import io.temporal.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskFailedRequest;
import java.util.HashMap;
import java.util.Map;
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

  private class TaskHandlerImpl
      implements PollTaskExecutor.TaskHandler<PollForActivityTaskResponse> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(PollForActivityTaskResponse task) throws Exception {
      Scope metricsScope =
          options
              .getMetricsScope()
              .tagged(
                  ImmutableMap.of(
                      MetricsTag.ACTIVITY_TYPE,
                      task.getActivityType().getName(),
                      MetricsTag.WORKFLOW_TYPE,
                      task.getWorkflowType().getName()));

      metricsScope
          .timer(MetricsType.ACTIVITY_SCHEDULED_TO_START_LATENCY)
          .record(
              Duration.ofNanos(
                  task.getStartedTimestamp() - task.getScheduledTimestampOfThisAttempt()));

      // The following tags are for logging.
      MDC.put(LoggerTag.ACTIVITY_ID, task.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, task.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, task.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.RUN_ID, task.getWorkflowExecution().getRunId());

      propagateContext(task);

      try {
        Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_EXEC_LATENCY).start();
        ActivityTaskHandler.Result response = handler.handle(task, metricsScope, false);
        sw.stop();

        sw = metricsScope.timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
        sendReply(task, response, metricsScope);
        sw.stop();

        long nanoTime =
            TimeUnit.NANOSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        Duration duration = Duration.ofNanos(nanoTime - task.getScheduledTimestampOfThisAttempt());
        metricsScope.timer(MetricsType.ACTIVITY_E2E_LATENCY).record(duration);

      } catch (FailureWrapperException e) {
        Failure failure = e.getFailure();
        if (failure.hasCanceledFailureInfo()) {
          CanceledFailureInfo info = failure.getCanceledFailureInfo();
          RespondActivityTaskCanceledRequest.Builder cancelledRequest =
              RespondActivityTaskCanceledRequest.newBuilder().setIdentity(options.getIdentity());
          if (info.hasDetails()) {
            cancelledRequest.setDetails(info.getDetails());
          }
          Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
          sendReply(task, new Result(null, null, cancelledRequest.build(), null), metricsScope);
          sw.stop();
        }
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.RUN_ID);
      }
    }

    void propagateContext(PollForActivityTaskResponse response) {
      if (options.getContextPropagators() == null || options.getContextPropagators().isEmpty()) {
        return;
      }

      if (!response.hasHeader()) {
        return;
      }
      Map<String, Payload> headerData = new HashMap<>();
      for (Map.Entry<String, Payload> entry : response.getHeader().getFieldsMap().entrySet()) {
        headerData.put(entry.getKey(), entry.getValue());
      }
      for (ContextPropagator propagator : options.getContextPropagators()) {
        propagator.setCurrentContext(propagator.deserializeContext(headerData));
      }
    }

    @Override
    public Throwable wrapFailure(PollForActivityTaskResponse task, Throwable failure) {
      WorkflowExecution execution = task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing activity task. WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + ", ActivityType="
              + task.getActivityType().getName()
              + ", ActivityId="
              + task.getActivityId(),
          failure);
    }

    private void sendReply(
        PollForActivityTaskResponse task, ActivityTaskHandler.Result response, Scope metricsScope) {
      RpcRetryOptions ro = response.getRequestRetryOptions();
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = RpcRetryOptions.newBuilder().setRetryOptions(ro).validateBuildWithDefaults();
        RespondActivityTaskCompletedRequest request =
            taskCompleted
                .toBuilder()
                .setTaskToken(task.getTaskToken())
                .setIdentity(options.getIdentity())
                .build();
        GrpcRetryer.retry(ro, () -> service.blockingStub().respondActivityTaskCompleted(request));
        metricsScope.counter(MetricsType.ACTIVITY_TASK_COMPLETED_COUNTER).inc(1);
      } else {
        Result.TaskFailedResult taskFailed = response.getTaskFailed();

        if (taskFailed != null) {
          RespondActivityTaskFailedRequest request =
              taskFailed
                  .getTaskFailedRequest()
                  .toBuilder()
                  .setTaskToken(task.getTaskToken())
                  .setIdentity(options.getIdentity())
                  .build();
          ro = RpcRetryOptions.newBuilder().setRetryOptions(ro).validateBuildWithDefaults();

          GrpcRetryer.retry(ro, () -> service.blockingStub().respondActivityTaskFailed(request));
          metricsScope.counter(MetricsType.ACTIVITY_TASK_FAILED_COUNTER).inc(1);
        } else {
          RespondActivityTaskCanceledRequest taskCancelled = response.getTaskCancelled();
          if (taskCancelled != null) {
            RespondActivityTaskCanceledRequest request =
                taskCancelled
                    .toBuilder()
                    .setTaskToken(task.getTaskToken())
                    .setIdentity(options.getIdentity())
                    .build();
            ro = RpcRetryOptions.newBuilder().setRetryOptions(ro).validateBuildWithDefaults();

            GrpcRetryer.retry(
                ro, () -> service.blockingStub().respondActivityTaskCanceled(request));
            metricsScope.counter(MetricsType.ACTIVITY_TASK_CANCELED_COUNTER).inc(1);
          }
        }
      }
      // Manual activity completion
    }
  }
}
