/*
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

package com.uber.cadence.internal.worker;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.MarkerRecordedEventAttributes;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.LocalActivityMarkerData;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.replay.ClockDecisionContext;
import com.uber.cadence.internal.replay.ExecuteLocalActivityParameters;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class LocalActivityWorker implements SuspendableWorker {

  private static final String POLL_THREAD_NAME_PREFIX = "Local Activity Poller taskList=";

  private SuspendableWorker poller = new NoopSuspendableWorker();
  private final ActivityTaskHandler handler;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;
  private final LocalActivityPollTask laPollTask;

  public LocalActivityWorker(
      String domain, String taskList, SingleWorkerOptions options, ActivityTaskHandler handler) {
    this.domain = Objects.requireNonNull(domain);
    this.taskList = Objects.requireNonNull(taskList);
    this.handler = handler;
    this.laPollTask = new LocalActivityPollTask();

    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          new PollerOptions.Builder(pollerOptions)
              .setPollThreadNamePrefix(
                  POLL_THREAD_NAME_PREFIX + "\"" + taskList + "\", domain=\"" + domain + "\"")
              .build();
    }
    this.options = new SingleWorkerOptions.Builder(options).setPollerOptions(pollerOptions).build();
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      poller =
          new Poller<>(
              options.getIdentity(),
              laPollTask,
              new PollTaskExecutor<>(domain, taskList, options, new TaskHandlerImpl(handler)),
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

      if (result.getTaskCompleted() != null) {
        markerBuilder.setResult(result.getTaskCompleted().getResult());
      } else if (result.getTaskFailedResult() != null) {
        markerBuilder.setTaskFailedRequest(result.getTaskFailedResult().getTaskFailedRequest());
        markerBuilder.setAttempt(result.getAttempt());
        markerBuilder.setBackoff(result.getBackoff());
      } else {
        markerBuilder.setTaskCancelledRequest(result.getTaskCancelled());
      }

      LocalActivityMarkerData marker = markerBuilder.build();

      HistoryEvent event = new HistoryEvent();
      event.setEventType(EventType.MarkerRecorded);
      MarkerRecordedEventAttributes attributes =
          new MarkerRecordedEventAttributes()
              .setMarkerName(ClockDecisionContext.LOCAL_ACTIVITY_MARKER_NAME)
              .setHeader(marker.getHeader(options.getDataConverter()))
              .setDetails(marker.getResult());
      event.setMarkerRecordedEventAttributes(attributes);
      task.eventConsumer.accept(event);
    }

    @Override
    public Throwable wrapFailure(Task task, Throwable failure) {
      return new RuntimeException("Failure processing local activity task.", failure);
    }

    private ActivityTaskHandler.Result handleLocalActivity(Task task) throws InterruptedException {
      Map<String, String> activityTypeTag =
          new ImmutableMap.Builder<String, String>(1)
              .put(MetricsTag.ACTIVITY_TYPE, task.params.getActivityType().getName())
              .build();

      Scope metricsScope = options.getMetricsScope().tagged(activityTypeTag);
      metricsScope.counter(MetricsType.LOCAL_ACTIVITY_TOTAL_COUNTER).inc(1);

      PollForActivityTaskResponse pollTask = new PollForActivityTaskResponse();
      pollTask.setActivityType(task.params.getActivityType());
      pollTask.setInput(task.params.getInput());
      pollTask.setAttempt(task.params.getAttempt());

      Stopwatch sw = metricsScope.timer(MetricsType.LOCAL_ACTIVITY_EXECUTION_LATENCY).start();
      ActivityTaskHandler.Result result = handler.handle(pollTask, metricsScope, true);
      sw.stop();
      result.setAttempt(task.params.getAttempt());

      if (result.getTaskCompleted() != null
          || result.getTaskCancelled() != null
          || task.params.getRetryOptions() == null) {
        return result;
      }

      RetryOptions retryOptions = task.params.getRetryOptions();
      long sleepMillis = retryOptions.calculateSleepTime(task.params.getAttempt());
      long elapsedTask = System.currentTimeMillis() - task.taskStartTime;
      long elapsedTotal = elapsedTask + task.params.getElapsedTime();
      if (retryOptions.shouldRethrow(
          result.getTaskFailedResult().getFailure(),
          task.params.getAttempt(),
          elapsedTotal,
          sleepMillis)) {
        return result;
      } else {
        result.setBackoff(Duration.ofMillis(sleepMillis));
      }

      // For small backoff we do local retry. Otherwise we will schedule timer on server side.
      if (elapsedTask + sleepMillis < task.decisionTimeoutSeconds * 1000) {
        Thread.sleep(sleepMillis);
        task.params.setAttempt(task.params.getAttempt() + 1);
        return handleLocalActivity(task);
      } else {
        return result;
      }
    }
  }
}
