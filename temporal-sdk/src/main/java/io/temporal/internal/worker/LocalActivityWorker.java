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

import static io.temporal.internal.worker.LocalActivityResult.failed;

import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Deadline;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class LocalActivityWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(LocalActivityWorker.class);

  // RETRY_STATE_IN_PROGRESS shows that it's not the end
  // for this local activity execution from the workflow point of view.
  // It's also not conflicting with any other situations
  // and uniquely identifies the reach of the local retries
  // and a need to schedule a timer.
  private static final RetryState LOCAL_RETRY_LIMIT_RETRY_STATE =
      RetryState.RETRY_STATE_IN_PROGRESS;

  private final ActivityTaskHandler handler;
  private final String namespace;
  private final String taskQueue;
  private final ScheduledExecutorService scheduledExecutor;

  private final SingleWorkerOptions options;

  private static final int QUEUE_SIZE = 1000;
  private final BlockingQueue<LocalActivityAttemptTask> pendingTasks =
      new ArrayBlockingQueue<>(QUEUE_SIZE);
  private final LocalActivityPollTask laPollTask;
  private final LocalActivityDispatcherImpl laScheduler;

  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;

  @Nonnull private SuspendableWorker poller = new NoopSuspendableWorker();

  public LocalActivityWorker(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull SingleWorkerOptions options,
      @Nonnull ActivityTaskHandler handler) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.scheduledExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r);
              thread.setName(
                  WorkerThreadsNameHelper.getLocalActivitySchedulerThreadPrefix(
                      namespace, taskQueue));
              return thread;
            });
    this.handler = handler;
    this.laPollTask = new LocalActivityPollTask(pendingTasks);
    this.laScheduler = new LocalActivityDispatcherImpl();
    this.options = Objects.requireNonNull(options);
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(
            options.getMetricsScope(), WorkerMetricsTag.WorkerType.LOCAL_ACTIVITY_WORKER);
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      PollTaskExecutor<LocalActivityAttemptTask> pollTaskExecutor =
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

  private boolean submitAnAttempt(LocalActivityAttemptTask task) {
    try {
      @Nullable
      Duration scheduleToStartTimeout = task.getExecutionParams().getScheduleToStartTimeout();
      boolean accepted =
          scheduleToStartTimeout != null
              ? pendingTasks.offer(task, scheduleToStartTimeout.toMillis(), TimeUnit.MILLISECONDS)
              : pendingTasks.offer(task);
      if (accepted) {
        log.trace("LocalActivity queued: {}", task.getActivityId());
      } else {
        log.trace(
            "LocalActivity queue submitting timed out for activity {}, scheduleToStartTimeout: {}",
            task.getActivityId(),
            scheduleToStartTimeout);
      }
      return accepted;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * @param executionContext execution context of the activity
   * @param activityTask activity task
   * @param executionThrowable exception happened during the activity execution. Can be null (for
   *     startToClose timeout)
   * @return decision to retry or not with a retry state, backoff or delay to the next attempt if
   *     applicable
   */
  @Nonnull
  private RetryDecision shouldRetry(
      LocalActivityExecutionContext executionContext,
      PollActivityTaskQueueResponse activityTask,
      @Nullable Throwable executionThrowable) {
    int currentAttempt = activityTask.getAttempt();

    if (isNonRetryableApplicationFailure(executionThrowable)) {
      return new RetryDecision(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, null);
    }

    if (executionThrowable instanceof Error) {
      // TODO Error inside Local Activity shouldn't be failing the local activity call.
      //  Instead we should fail Workflow Task. Implement a special flag for that in the result.
      //          task.callback(executionFailed(activityHandlerResult,
      // RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, currentAttempt));
      // don't just swallow Error from activities, propagate it to the top
      throw (Error) executionThrowable;
    }

    if (isRetryPolicyNotSet(activityTask)) {
      return new RetryDecision(RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET, null);
    }

    RetryOptions retryOptions = RetryOptionsUtils.toRetryOptions(activityTask.getRetryPolicy());

    if (RetryOptionsUtils.isNotRetryable(retryOptions, executionThrowable)) {
      return new RetryDecision(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, null);
    }

    if (RetryOptionsUtils.areAttemptsReached(retryOptions, currentAttempt)) {
      return new RetryDecision(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, null);
    }

    long sleepMillis = retryOptions.calculateSleepTime(currentAttempt);
    if (RetryOptionsUtils.isDeadlineReached(
        executionContext.getScheduleToCloseDeadline(), sleepMillis)) {
      return new RetryDecision(RetryState.RETRY_STATE_TIMEOUT, null);
    }

    if (executionContext.getLocalRetryDeadline().timeRemaining(TimeUnit.MILLISECONDS)
        <= sleepMillis) {
      return new RetryDecision(LOCAL_RETRY_LIMIT_RETRY_STATE, Duration.ofMillis(sleepMillis));
    }

    return new RetryDecision(Duration.ofMillis(sleepMillis));
  }

  /**
   * @param executionContext execution context of the activity
   * @param backoff delay time in milliseconds to the next attempt
   * @param failure if supplied, it will be used to override {@link
   *     LocalActivityExecutionContext#getLastFailure()}
   */
  private void scheduleNextAttempt(
      LocalActivityExecutionContext executionContext,
      @Nonnull Duration backoff,
      @Nullable Failure failure) {
    PollActivityTaskQueueResponse nextActivityTask =
        executionContext.getNextAttemptActivityTask(failure);
    LocalActivityAttemptTask task =
        new LocalActivityAttemptTask(executionContext, nextActivityTask);
    Deadline.after(backoff.toMillis(), TimeUnit.MILLISECONDS)
        .runOnExpiration(new LocalActivityRetryHandler(task), scheduledExecutor);
  }

  private class LocalActivityDispatcherImpl implements LocalActivityDispatcher {
    @Override
    public boolean dispatch(
        ExecuteLocalActivityParameters params,
        Functions.Proc1<LocalActivityResult> resultCallback) {

      long localRetryThresholdMs = params.getLocalRetryThreshold().toMillis();
      Preconditions.checkState(localRetryThresholdMs > 0, "localRetryThresholdMs must be > 0");
      Deadline localRetryDeadline = Deadline.after(localRetryThresholdMs, TimeUnit.MILLISECONDS);

      long passedFromOriginalSchedulingMs = 0;
      if (params.getOriginalScheduledTimestamp() != ExecuteLocalActivityParameters.NOT_SCHEDULED) {
        passedFromOriginalSchedulingMs =
            System.currentTimeMillis() - params.getOriginalScheduledTimestamp();
      } else {
        params.setOriginalScheduledTimestamp(System.currentTimeMillis());
      }

      Duration scheduleToCloseTimeout = params.getScheduleToCloseTimeout();
      Deadline scheduleToCloseDeadline = null;
      if (scheduleToCloseTimeout != null) {
        scheduleToCloseDeadline =
            Deadline.after(
                scheduleToCloseTimeout.toMillis() - passedFromOriginalSchedulingMs,
                TimeUnit.MILLISECONDS);
      }

      LocalActivityExecutionContext executionContext =
          new LocalActivityExecutionContext(
              params, resultCallback, localRetryDeadline, scheduleToCloseDeadline);

      LocalActivityAttemptTask task =
          new LocalActivityAttemptTask(executionContext, params.getInitialActivityTask());
      boolean accepted = submitAnAttempt(task);

      if (accepted) {
        if (scheduleToCloseDeadline != null) {
          ScheduledFuture<?> scheduledScheduleToClose =
              scheduledExecutor.schedule(
                  new ScheduleToCloseTimeoutHandler(executionContext),
                  scheduleToCloseDeadline.timeRemaining(TimeUnit.MILLISECONDS),
                  TimeUnit.MILLISECONDS);
          executionContext.setScheduleToCloseFuture(scheduledScheduleToClose);
        }
      }

      return accepted;
    }
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<LocalActivityAttemptTask> {

    private final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(LocalActivityAttemptTask attemptTask) throws Exception {
      LocalActivityExecutionContext executionContext = attemptTask.getExecutionContext();
      PollActivityTaskQueueResponse activityTask = attemptTask.getAttemptTask();
      String activityId = activityTask.getActivityId();

      Scope metricsScope =
          workerMetricsScope.tagged(
              ImmutableMap.of(
                  MetricsTag.ACTIVITY_TYPE,
                  activityTask.getActivityType().getName(),
                  MetricsTag.WORKFLOW_TYPE,
                  activityTask.getWorkflowType().getName()));

      int currentAttempt = activityTask.getAttempt();

      MDC.put(LoggerTag.ACTIVITY_ID, activityId);
      MDC.put(LoggerTag.ACTIVITY_TYPE, activityTask.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, activityTask.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, activityTask.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, activityTask.getWorkflowExecution().getRunId());
      try {
        ScheduledFuture<?> startToCloseTimeoutFuture = null;

        if (activityTask.hasStartToCloseTimeout()) {
          startToCloseTimeoutFuture =
              scheduledExecutor.schedule(
                  new StartToCloseTimeoutHandler(attemptTask),
                  ProtobufTimeUtils.toJavaDuration(
                          attemptTask.getAttemptTask().getStartToCloseTimeout())
                      .toMillis(),
                  TimeUnit.MILLISECONDS);
        }

        metricsScope.counter(MetricsType.LOCAL_ACTIVITY_TOTAL_COUNTER).inc(1);

        ActivityTaskHandler.Result activityHandlerResult;
        Stopwatch sw = metricsScope.timer(MetricsType.LOCAL_ACTIVITY_EXECUTION_LATENCY).start();
        try {
          activityHandlerResult =
              handler.handle(new ActivityTask(activityTask, () -> {}), metricsScope, true);
        } finally {
          sw.stop();
        }

        // Making sure that the result handling code following this statement is mutual exclusive
        // with the start to close timeout handler.
        boolean startToCloseTimeoutFired =
            startToCloseTimeoutFuture != null && !startToCloseTimeoutFuture.cancel(false);

        if (startToCloseTimeoutFired) {
          // If start to close timeout fired, the result of this activity execution should be
          // discarded.
          // Scheduling of the next attempt is taken care by the StartToCloseTimeoutHandler.
          return;
        }

        if (activityHandlerResult.getTaskCompleted() != null) {
          com.uber.m3.util.Duration e2eDuration =
              ProtobufTimeUtils.toM3DurationSinceNow(activityTask.getScheduledTime());
          metricsScope.timer(MetricsType.LOCAL_ACTIVITY_SUCCEED_E2E_LATENCY).record(e2eDuration);
          executionContext.callback(LocalActivityResult.completed(activityHandlerResult));
          return;
        }

        if (activityHandlerResult.getTaskCanceled() != null) {
          executionContext.callback(LocalActivityResult.cancelled(activityHandlerResult));
          return;
        }

        Preconditions.checkState(
            activityHandlerResult.getTaskFailed() != null,
            "One of taskCompleted, taskCanceled or taskFailed must be set");

        Failure executionFailure =
            activityHandlerResult.getTaskFailed().getTaskFailedRequest().getFailure();

        RetryDecision retryDecision =
            shouldRetry(
                executionContext, activityTask, activityHandlerResult.getTaskFailed().getFailure());
        if (retryDecision.doNextAttempt()) {
          scheduleNextAttempt(
              executionContext,
              Objects.requireNonNull(
                  retryDecision.nextAttemptBackoff,
                  "nextAttemptBackoff is expected to not be null"),
              executionFailure);
        } else {
          executionContext.callback(
              failed(
                  activityId,
                  retryDecision.retryState,
                  executionFailure,
                  currentAttempt,
                  retryDecision.nextAttemptBackoff));
        }

      } catch (Throwable ex) {
        // handleLocalActivity is expected to never throw an exception and return a result
        // that can be used for a workflow callback if this method throws, it's a bug.
        log.error("[BUG] Code that expected to never throw an exception threw an exception", ex);
        Failure failure = FailureConverter.exceptionToFailure(ex);
        executionContext.callback(
            failed(
                activityId,
                RetryState.RETRY_STATE_INTERNAL_SERVER_ERROR,
                failure,
                currentAttempt,
                null));
        throw ex;
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);
      }
    }

    @Override
    public Throwable wrapFailure(LocalActivityAttemptTask task, Throwable failure) {
      return new RuntimeException("Failure processing local activity task.", failure);
    }
  }

  private class LocalActivityRetryHandler implements Runnable {
    private final LocalActivityAttemptTask localActivityAttemptTask;

    private LocalActivityRetryHandler(LocalActivityAttemptTask localActivityAttemptTask) {
      this.localActivityAttemptTask = localActivityAttemptTask;
    }

    @Override
    public void run() {
      submitAnAttempt(localActivityAttemptTask);
    }
  }

  private static class ScheduleToCloseTimeoutHandler implements Runnable {
    private final LocalActivityExecutionContext executionContext;

    private ScheduleToCloseTimeoutHandler(LocalActivityExecutionContext executionContext) {
      this.executionContext = executionContext;
    }

    @Override
    public void run() {
      executionContext.callback(
          failed(
              executionContext.getActivityId(),
              RetryState.RETRY_STATE_TIMEOUT,
              newTimeoutFailure(
                  TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, executionContext.getLastFailure()),
              executionContext.getCurrentAttempt(),
              null));
    }
  }

  private class StartToCloseTimeoutHandler implements Runnable {
    private final LocalActivityAttemptTask attemptTask;

    private StartToCloseTimeoutHandler(LocalActivityAttemptTask attemptTask) {
      this.attemptTask = attemptTask;
    }

    @Override
    public void run() {
      LocalActivityExecutionContext executionContext = attemptTask.getExecutionContext();
      PollActivityTaskQueueResponse activityTask = attemptTask.getAttemptTask();
      String activityId = activityTask.getActivityId();

      int timingOutAttempt = activityTask.getAttempt();

      RetryDecision retryDecision = shouldRetry(executionContext, activityTask, null);
      if (retryDecision.doNextAttempt()) {
        scheduleNextAttempt(
            executionContext,
            Objects.requireNonNull(
                retryDecision.nextAttemptBackoff, "nextAttemptBackoff is expected to not be null"),
            // null because schedule to start / close is not meaningful
            newTimeoutFailure(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, null));
      } else {
        // RetryState.RETRY_STATE_TIMEOUT happens only when scheduleToClose is fired
        // scheduleToClose timeout is effectively replacing the original startToClose
        TimeoutType timeoutType =
            RetryState.RETRY_STATE_TIMEOUT.equals(retryDecision.retryState)
                ? TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE
                : TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE;
        executionContext.callback(
            failed(
                activityId,
                retryDecision.retryState,
                newTimeoutFailure(timeoutType, executionContext.getLastFailure()),
                timingOutAttempt,
                retryDecision.nextAttemptBackoff));
      }
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
    return poller.isTerminated() && scheduledExecutor.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return poller
        .shutdown(shutdownManager, interruptTasks)
        .thenCompose(
            r ->
                shutdownManager.shutdownExecutor(
                    scheduledExecutor, this + "#scheduledExecutor", Duration.ofSeconds(1)))
        .exceptionally(
            e -> {
              log.error("[BUG] Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    timeoutMillis = ShutdownManager.awaitTermination(poller, timeoutMillis);
    ShutdownManager.awaitTermination(scheduledExecutor, timeoutMillis);
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

  public LocalActivityDispatcher getLocalActivityScheduler() {
    return laScheduler;
  }

  private static Failure newTimeoutFailure(TimeoutType timeoutType, @Nullable Failure cause) {
    TimeoutFailureInfo.Builder info = TimeoutFailureInfo.newBuilder().setTimeoutType(timeoutType);
    Failure.Builder result = Failure.newBuilder().setTimeoutFailureInfo(info);
    if (cause != null) {
      result.setCause(cause);
    }
    return result.build();
  }

  private static boolean isRetryPolicyNotSet(
      PollActivityTaskQueueResponseOrBuilder pollActivityTask) {
    return !pollActivityTask.hasScheduleToCloseTimeout()
        && (!pollActivityTask.hasRetryPolicy()
            || pollActivityTask.getRetryPolicy().getMaximumAttempts() <= 0);
  }

  private static boolean isNonRetryableApplicationFailure(@Nullable Throwable executionThrowable) {
    return executionThrowable instanceof ApplicationFailure
        && ((ApplicationFailure) executionThrowable).isNonRetryable();
  }

  private static class RetryDecision {
    private final @Nullable RetryState retryState;
    private final @Nullable Duration nextAttemptBackoff;

    // No next local attempts
    public RetryDecision(@Nonnull RetryState retryState, @Nullable Duration nextAttemptBackoff) {
      this.retryState = retryState;
      this.nextAttemptBackoff = nextAttemptBackoff;
    }

    // Do the next attempt
    public RetryDecision(@Nonnull Duration nextAttemptBackoff) {
      this.retryState = null;
      this.nextAttemptBackoff = Objects.requireNonNull(nextAttemptBackoff);
    }

    public boolean doNextAttempt() {
      return retryState == null;
    }
  }
}
