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

import com.google.protobuf.util.Timestamps;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

final class LocalActivityWorker implements SuspendableWorker {

  @Nonnull private SuspendableWorker poller = new NoopSuspendableWorker();
  private final ActivityTaskHandler handler;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final LocalActivityPollTask laPollTask;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;

  public LocalActivityWorker(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull SingleWorkerOptions options,
      @Nonnull ActivityTaskHandler handler) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = handler;
    this.laPollTask = new LocalActivityPollTask();
    this.options = Objects.requireNonNull(options);
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(
            options.getMetricsScope(), WorkerMetricsTag.WorkerType.LOCAL_ACTIVITY_WORKER);
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      PollTaskExecutor<LocalActivityTask> pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              options.getTaskExecutorThreadPoolSize(),
              workerMetricsScope);
      poller =
          new Poller<>(
              options.getIdentity(),
              laPollTask,
              pollTaskExecutor,
              pollerOptions,
              workerMetricsScope);
      poller.start();
      workerMetricsScope.counter(MetricsType.WORKER_START_COUNTER).inc(1);
    }
  }

  public boolean isAnyTypeSupported() {
    return handler.isAnyTypeSupported();
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
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return poller.shutdown(shutdownManager, interruptTasks);
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

  private PollerOptions getPollerOptions(SingleWorkerOptions options) {
    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  WorkerThreadsNameHelper.getLocalActivityPollerThreadPrefix(namespace, taskQueue))
              .build();
    }
    return pollerOptions;
  }

  public BiFunction<LocalActivityTask, Duration, Boolean> getLocalActivityTaskPoller() {
    return laPollTask;
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<LocalActivityTask> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(LocalActivityTask task) throws Exception {
      ActivityTaskHandler.Result result = handleLocalActivity(task, System.currentTimeMillis());
      task.getEventConsumer().apply(result);
    }

    @Override
    public Throwable wrapFailure(LocalActivityTask task, Throwable failure) {
      return new RuntimeException("Failure processing local activity task.", failure);
    }

    private ActivityTaskHandler.Result handleLocalActivity(
        LocalActivityTask task, long activityStartTimeMs) throws InterruptedException {
      ExecuteLocalActivityParameters params = task.getParams();
      PollActivityTaskQueueResponse.Builder activityTask = params.getActivityTask();
      Map<String, String> activityTypeTag =
          new ImmutableMap.Builder<String, String>(1)
              .put(MetricsTag.ACTIVITY_TYPE, activityTask.getActivityType().getName())
              .put(MetricsTag.WORKFLOW_TYPE, activityTask.getWorkflowType().getName())
              .build();

      Scope metricsScope = workerMetricsScope.tagged(activityTypeTag);
      metricsScope.counter(MetricsType.LOCAL_ACTIVITY_TOTAL_COUNTER).inc(1);

      if (activityTask.hasHeader()) {
        ActivityWorkerHelper.deserializeAndPopulateContext(
            activityTask.getHeader(), options.getContextPropagators());
      }

      Stopwatch sw = metricsScope.timer(MetricsType.LOCAL_ACTIVITY_EXECUTION_LATENCY).start();
      ActivityTaskHandler.Result result =
          handler.handle(new ActivityTask(activityTask.build(), () -> {}), metricsScope, true);
      sw.stop();
      int attempt = activityTask.getAttempt();
      result.setAttempt(attempt);

      if (isNonRetryableApplicationFailure(result)) {
        return result;
      }

      if (result.getTaskCompleted() != null) {
        com.uber.m3.util.Duration e2eDuration =
            ProtobufTimeUtils.toM3DurationSinceNow(
                task.getParams().getActivityTask().getScheduledTime());
        metricsScope.timer(MetricsType.LOCAL_ACTIVITY_SUCCEED_E2E_LATENCY).record(e2eDuration);
      }

      if (result.getTaskCompleted() != null
          || result.getTaskCanceled() != null
          || !activityTask.hasRetryPolicy()) {
        return result;
      }

      RetryOptions retryOptions = buildRetryOptions(activityTask.getRetryPolicy());

      long sleepMillis = retryOptions.calculateSleepTime(attempt);
      long elapsedTask = System.currentTimeMillis() - activityStartTimeMs;
      long sinceScheduled =
          System.currentTimeMillis() - Timestamps.toMillis(activityTask.getScheduledTime());
      long elapsedTotal = elapsedTask + sinceScheduled;
      Duration timeout = ProtobufTimeUtils.toJavaDuration(activityTask.getScheduleToCloseTimeout());
      Optional<Duration> expiration =
          timeout.compareTo(Duration.ZERO) > 0 ? Optional.of(timeout) : Optional.empty();
      if (retryOptions.shouldRethrow(
          result.getTaskFailed().getFailure(), expiration, attempt, elapsedTotal, sleepMillis)) {
        return result;
      } else {
        result.setBackoff(Duration.ofMillis(sleepMillis));
      }

      // For small backoff we do local retry. Otherwise we will schedule timer on server side.
      // TODO(maxim): Use timer queue for retries to avoid tying up a thread.
      if (elapsedTask + sleepMillis < task.getParams().getLocalRetryThreshold().toMillis()) {
        Thread.sleep(sleepMillis);
        activityTask.setAttempt(attempt + 1);
        return handleLocalActivity(task, activityStartTimeMs);
      } else {
        return result;
      }
    }
  }

  static RetryOptions buildRetryOptions(RetryPolicy retryPolicy) {
    String[] doNotRetry = new String[retryPolicy.getNonRetryableErrorTypesCount()];
    retryPolicy.getNonRetryableErrorTypesList().toArray(doNotRetry);
    RetryOptions.Builder roBuilder = RetryOptions.newBuilder();
    Duration maximumInterval = ProtobufTimeUtils.toJavaDuration(retryPolicy.getMaximumInterval());
    if (!maximumInterval.isZero()) {
      roBuilder.setMaximumInterval(maximumInterval);
    }
    Duration initialInterval = ProtobufTimeUtils.toJavaDuration(retryPolicy.getInitialInterval());
    if (!initialInterval.isZero()) {
      roBuilder.setInitialInterval(initialInterval);
    }
    if (retryPolicy.getBackoffCoefficient() >= 1) {
      roBuilder.setBackoffCoefficient(retryPolicy.getBackoffCoefficient());
    }
    if (retryPolicy.getMaximumAttempts() > 0) {
      roBuilder.setMaximumAttempts(retryPolicy.getMaximumAttempts());
    }
    return roBuilder.setDoNotRetry(doNotRetry).validateBuildWithDefaults();
  }

  private static boolean isNonRetryableApplicationFailure(ActivityTaskHandler.Result result) {
    return result.getTaskFailed() != null
        && result.getTaskFailed().getFailure() != null
        && result.getTaskFailed().getFailure() instanceof ApplicationFailure
        && ((ApplicationFailure) result.getTaskFailed().getFailure()).isNonRetryable();
  }
}
