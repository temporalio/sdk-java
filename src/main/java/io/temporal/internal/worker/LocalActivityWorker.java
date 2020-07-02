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
import com.uber.m3.util.ImmutableMap;
import io.temporal.common.RetryOptions;
import io.temporal.history.v1.HistoryEvent;
import io.temporal.internal.common.LocalActivityMarkerData;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.workflowservice.v1.PollForActivityTaskResponse;
import io.temporal.workflowservice.v1.RespondActivityTaskCompletedRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class LocalActivityWorker implements SuspendableWorker {

  private static final String POLL_THREAD_NAME_PREFIX = "Local Activity Poller taskQueue=";

  private SuspendableWorker poller = new NoopSuspendableWorker();
  private final ActivityTaskHandler handler;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final LocalActivityPollTask laPollTask;

  public LocalActivityWorker(
      String namespace,
      String taskQueue,
      SingleWorkerOptions options,
      ActivityTaskHandler handler) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = handler;
    this.laPollTask = new LocalActivityPollTask();

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
              laPollTask,
              new PollTaskExecutor<>(namespace, taskQueue, options, new TaskHandlerImpl(handler)),
              options.getPollerOptions(),
              options.getMetricsScope());
      poller.start();
      options.getMetricsScope().counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  @Override
  public boolean isStarted() {
    if (poller == null) {
      return false;
    }
    return poller.isStarted();
  }

  @Override
  public boolean isShutdown() {
    if (poller == null) {
      return true;
    }
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    if (poller == null) {
      return true;
    }
    return poller.isTerminated();
  }

  @Override
  public void shutdown() {
    if (poller == null) {
      return;
    }
    poller.shutdown();
  }

  @Override
  public void shutdownNow() {
    if (poller == null) {
      return;
    }
    poller.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    poller.awaitTermination(timeout, unit);
  }

  @Override
  public void suspendPolling() {
    if (poller == null) {
      return;
    }
    poller.suspendPolling();
  }

  @Override
  public void resumePolling() {
    if (poller == null) {
      return;
    }
    poller.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    if (poller == null) {
      return true;
    }
    return poller.isSuspended();
  }

  public static class Task {
    private final ExecuteLocalActivityParameters params;
    private final Consumer<HistoryEvent> eventConsumer;
    private final LongSupplier currentTimeMillis;
    private final LongSupplier replayTimeUpdatedAtMillis;
    long taskStartTime;
    private final int decisionTimeoutSeconds;

    public Task(
        ExecuteLocalActivityParameters params,
        Consumer<HistoryEvent> eventConsumer,
        int decisionTimeoutSeconds,
        LongSupplier currentTimeMillis,
        LongSupplier replayTimeUpdatedAtMillis) {
      this.params = params;
      this.eventConsumer = eventConsumer;
      this.currentTimeMillis = currentTimeMillis;
      this.replayTimeUpdatedAtMillis = replayTimeUpdatedAtMillis;
      this.decisionTimeoutSeconds = decisionTimeoutSeconds;
    }
  }

  public BiFunction<Task, Duration, Boolean> getLocalActivityTaskPoller() {
    return laPollTask;
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<Task> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(Task task) throws Exception {
      task.taskStartTime = System.currentTimeMillis();
      ActivityTaskHandler.Result result = handleLocalActivity(task);

      LocalActivityMarkerData.Builder markerBuilder = new LocalActivityMarkerData.Builder();
      markerBuilder.setActivityId(task.params.getActivityId());
      markerBuilder.setActivityType(task.params.getActivityType());
      long replayTimeMillis =
          task.currentTimeMillis.getAsLong()
              + (System.currentTimeMillis() - task.replayTimeUpdatedAtMillis.getAsLong());
      markerBuilder.setReplayTimeMillis(replayTimeMillis);

      RespondActivityTaskCompletedRequest taskCompleted = result.getTaskCompleted();
      if (taskCompleted != null) {
        if (taskCompleted.hasResult()) {
          markerBuilder.setResult(taskCompleted.getResult());
        }
      } else if (result.getTaskFailed() != null) {
        markerBuilder.setTaskFailedRequest(result.getTaskFailed().getTaskFailedRequest());
        markerBuilder.setAttempt(result.getAttempt());
        markerBuilder.setBackoff(result.getBackoff());
      } else {
        markerBuilder.setTaskCancelledRequest(result.getTaskCancelled());
      }
      LocalActivityMarkerData marker = markerBuilder.build();
      HistoryEvent event = marker.toEvent(options.getDataConverter());
      task.eventConsumer.accept(event);
    }

    @Override
    public Throwable wrapFailure(Task task, Throwable failure) {
      return new RuntimeException("Failure processing local activity task.", failure);
    }

    private ActivityTaskHandler.Result handleLocalActivity(Task task) throws InterruptedException {
      ExecuteLocalActivityParameters p = task.params;
      Map<String, String> activityTypeTag =
          new ImmutableMap.Builder<String, String>(1)
              .put(MetricsTag.ACTIVITY_TYPE, p.getActivityType().getName())
              .build();

      Scope metricsScope = options.getMetricsScope().tagged(activityTypeTag);
      metricsScope.counter(MetricsType.LOCAL_ACTIVITY_TOTAL_COUNTER).inc(1);

      PollForActivityTaskResponse.Builder pollTask =
          PollForActivityTaskResponse.newBuilder()
              .setWorkflowNamespace(p.getWorkflowNamespace())
              .setActivityId(p.getActivityId())
              .setWorkflowExecution(p.getWorkflowExecution())
              .setScheduledTimestamp(System.currentTimeMillis())
              .setStartedTimestamp(System.currentTimeMillis())
              .setActivityType(p.getActivityType())
              .setAttempt(p.getAttempt());

      Duration scheduleToCloseTimeout = p.getScheduleToCloseTimeout();
      if (scheduleToCloseTimeout != null) {
        pollTask.setScheduleToCloseTimeoutSeconds(
            (int) Math.ceil(scheduleToCloseTimeout.toMillis() / 1000f));
      } else {
        pollTask.setScheduleToCloseTimeoutSeconds(task.decisionTimeoutSeconds);
      }
      Duration startToCloseTimeout = p.getStartToCloseTimeout();
      if (startToCloseTimeout != null) {
        pollTask.setStartToCloseTimeoutSeconds(
            (int) Math.ceil(startToCloseTimeout.toMillis() / 1000f));
      } else {
        pollTask.setStartToCloseTimeoutSeconds(pollTask.getScheduleToCloseTimeoutSeconds());
      }
      if (p.getInput() != null) {
        pollTask.setInput(p.getInput());
      }
      Stopwatch sw = metricsScope.timer(MetricsType.LOCAL_ACTIVITY_EXECUTION_LATENCY).start();
      ActivityTaskHandler.Result result = handler.handle(pollTask.build(), metricsScope, true);
      sw.stop();
      result.setAttempt(p.getAttempt());

      if (result.getTaskCompleted() != null
          || result.getTaskCancelled() != null
          || p.getRetryOptions() == null) {
        return result;
      }

      RetryOptions retryOptions = p.getRetryOptions();
      long sleepMillis = retryOptions.calculateSleepTime(p.getAttempt());
      long elapsedTask = System.currentTimeMillis() - task.taskStartTime;
      long elapsedTotal = elapsedTask + p.getElapsedTime();
      int timeoutSeconds = pollTask.getScheduleToCloseTimeoutSeconds();
      Optional<Duration> expiration =
          timeoutSeconds > 0 ? Optional.of(Duration.ofSeconds(timeoutSeconds)) : Optional.empty();
      if (retryOptions.shouldRethrow(
          result.getTaskFailed().getFailure(),
          expiration,
          p.getAttempt(),
          elapsedTotal,
          sleepMillis)) {
        return result;
      } else {
        result.setBackoff(Duration.ofMillis(sleepMillis));
      }

      // For small backoff we do local retry. Otherwise we will schedule timer on server side.
      if (elapsedTask + sleepMillis < task.decisionTimeoutSeconds * 1000) {
        Thread.sleep(sleepMillis);
        p.setAttempt(p.getAttempt() + 1);
        return handleLocalActivity(task);
      } else {
        return result;
      }
    }
  }
}
