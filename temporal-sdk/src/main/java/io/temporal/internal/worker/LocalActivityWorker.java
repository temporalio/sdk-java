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
import static io.temporal.internal.worker.LocalActivityResult.processingFailed;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.TimeLimiter;
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
import io.temporal.internal.activity.ActivityPollResponseToInfo;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class LocalActivityWorker implements Startable, Shutdownable {
  private static final Logger log = LoggerFactory.getLogger(LocalActivityWorker.class);
  private static final ExecutorService timeoutThreadPool = Executors.newCachedThreadPool();

  private final ActivityTaskHandler handler;
  private final String namespace;
  private final String taskQueue;

  private final SingleWorkerOptions options;

  private final LocalActivityDispatcherImpl laScheduler;

  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;

  private ScheduledExecutorService scheduledExecutor;
  private PollTaskExecutor<LocalActivityAttemptTask> activityAttemptTaskExecutor;
  private final TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier;

  public LocalActivityWorker(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull SingleWorkerOptions options,
      @Nonnull ActivityTaskHandler handler,
      @Nonnull TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = handler;
    this.slotSupplier = Objects.requireNonNull(slotSupplier);
    this.laScheduler = new LocalActivityDispatcherImpl(slotSupplier);
    this.options = Objects.requireNonNull(options);
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(
            options.getMetricsScope(), WorkerMetricsTag.WorkerType.LOCAL_ACTIVITY_WORKER);
    this.slotSupplier.setMetricsScope(this.workerMetricsScope);
  }

  private void submitRetry(
      @Nonnull LocalActivityExecutionContext executionContext,
      @Nonnull PollActivityTaskQueueResponse.Builder activityTask) {
    submitAttempt(executionContext, activityTask);
  }

  private void submitAttempt(
      @Nonnull LocalActivityExecutionContext executionContext,
      @Nonnull PollActivityTaskQueueResponse.Builder activityTask) {
    @Nullable Duration scheduleToStartTimeout = executionContext.getScheduleToStartTimeout();
    @Nullable ScheduledFuture<?> scheduleToStartFuture = null;
    if (scheduleToStartTimeout != null) {
      scheduleToStartFuture =
          scheduledExecutor.schedule(
              new FinalTimeoutHandler(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START, executionContext),
              scheduleToStartTimeout.toMillis(),
              TimeUnit.MILLISECONDS);
    }

    activityTask.setCurrentAttemptScheduledTime(ProtobufTimeUtils.getCurrentProtoTime());
    LocalActivityAttemptTask task =
        new LocalActivityAttemptTask(executionContext, activityTask, scheduleToStartFuture);
    activityAttemptTaskExecutor.process(task);
  }

  /**
   * @param executionContext execution context of the activity
   * @param activityTask activity task
   * @param attemptThrowable exception happened during the activity attempt. Can be null.
   * @return decision to retry or not with a retry state, backoff or delay to the next attempt if
   *     applicable
   */
  @Nonnull
  private RetryDecision shouldRetry(
      LocalActivityExecutionContext executionContext,
      PollActivityTaskQueueResponseOrBuilder activityTask,
      @Nullable Throwable attemptThrowable) {
    int currentAttempt = activityTask.getAttempt();

    if (isNonRetryableApplicationFailure(attemptThrowable)) {
      return new RetryDecision(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, null);
    }

    if (attemptThrowable instanceof Error) {
      // TODO Error inside Local Activity shouldn't be failing the local activity call.
      //  Instead we should fail Workflow Task. Implement a special flag for that in the result.
      //          task.callback(executionFailed(activityHandlerResult,
      // RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, currentAttempt));
      // don't just swallow Error from activities, propagate it to the top
      throw (Error) attemptThrowable;
    }

    if (isRetryPolicyNotSet(activityTask)) {
      return new RetryDecision(RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET, null);
    }

    RetryOptions retryOptions = RetryOptionsUtils.toRetryOptions(activityTask.getRetryPolicy());

    if (RetryOptionsUtils.isNotRetryable(retryOptions, attemptThrowable)) {
      return new RetryDecision(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE, null);
    }

    if (RetryOptionsUtils.areAttemptsReached(retryOptions, currentAttempt)) {
      return new RetryDecision(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, null);
    }

    Optional<Duration> nextRetryDelay = getNextRetryDelay(attemptThrowable);
    long sleepMillis = retryOptions.calculateSleepTime(currentAttempt);
    Duration sleep = nextRetryDelay.orElse(Duration.ofMillis(sleepMillis));
    if (RetryOptionsUtils.isDeadlineReached(
        executionContext.getScheduleToCloseDeadline(), sleepMillis)) {
      return new RetryDecision(RetryState.RETRY_STATE_TIMEOUT, null);
    }

    if (sleep.compareTo(executionContext.getLocalRetryThreshold()) > 0) {
      // RETRY_STATE_IN_PROGRESS shows that it's not the end for this local activity execution from
      // the workflow point of view. It's also not conflicting with any other situations and
      // uniquely identifies the reach of the local retries and a need to schedule a timer.
      return new RetryDecision(RetryState.RETRY_STATE_IN_PROGRESS, sleep);
    }

    return new RetryDecision(sleep);
  }

  /**
   * @param executionContext execution context of the activity
   * @param backoff delay time in milliseconds to the next attempt
   * @param failure if supplied, it will be used to override {@link
   *     LocalActivityExecutionContext#getLastAttemptFailure()}
   */
  private void scheduleNextAttempt(
      LocalActivityExecutionContext executionContext,
      @Nonnull Duration backoff,
      @Nullable Failure failure) {
    PollActivityTaskQueueResponse.Builder nextActivityTask =
        executionContext.getNextAttemptActivityTask(failure);
    Deadline.after(backoff.toMillis(), TimeUnit.MILLISECONDS)
        .runOnExpiration(
            new LocalActivityRetryHandler(executionContext, nextActivityTask), scheduledExecutor);
  }

  private class LocalActivityDispatcherImpl implements LocalActivityDispatcher {
    /**
     * Retries always get a green light, but we have a backpressure for new tasks if the queue fills
     * up with not picked up new executions
     */
    private final TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier;

    public LocalActivityDispatcherImpl(TrackingSlotSupplier<LocalActivitySlotInfo> slotSupplier) {
      // we allow submitters to block and wait till the workflow task heartbeat to allow the worker
      // to tolerate spikes of short local activity executions.
      this.slotSupplier = slotSupplier;
    }

    @Override
    public boolean dispatch(
        @Nonnull ExecuteLocalActivityParameters params,
        @Nonnull Functions.Proc1<LocalActivityResult> resultCallback,
        @Nullable Deadline acceptanceDeadline) {
      WorkerLifecycleState lifecycleState = getLifecycleState();
      switch (lifecycleState) {
        case NOT_STARTED:
          throw new IllegalStateException(
              "Local Activity Worker is not started, no activities were registered");
        case SHUTDOWN:
          throw new IllegalStateException("Local Activity Worker is shutdown");
        case TERMINATED:
          throw new IllegalStateException("Local Activity Worker is terminated");
        case SUSPENDED:
          throw new IllegalStateException(
              "[BUG] Local Activity Worker is suspended. Suspension is not supported for Local Activity Worker");
      }

      Preconditions.checkArgument(
          handler.isTypeSupported(params.getActivityType().getName()),
          "Activity type %s is not supported by the local activity worker",
          params.getActivityType().getName());

      long passedFromOriginalSchedulingMs =
          System.currentTimeMillis() - params.getOriginalScheduledTimestamp();
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
              params, resultCallback, scheduleToCloseDeadline, slotSupplier);

      PollActivityTaskQueueResponse.Builder activityTask = executionContext.getInitialTask();

      boolean retryIsNotAllowed =
          failIfRetryIsNotAllowedByNewPolicy(executionContext, activityTask);
      if (retryIsNotAllowed) {
        return true;
      }

      return submitANewExecution(executionContext, activityTask, acceptanceDeadline);
    }

    private boolean submitANewExecution(
        @Nonnull LocalActivityExecutionContext executionContext,
        @Nonnull PollActivityTaskQueueResponse.Builder activityTask,
        @Nullable Deadline heartbeatDeadline) {
      long acceptanceTimeoutMs = 0;
      boolean timeoutIsScheduleToStart = false;
      if (heartbeatDeadline != null) {
        acceptanceTimeoutMs = heartbeatDeadline.timeRemaining(TimeUnit.MILLISECONDS);
      }
      Duration scheduleToStartTimeout = executionContext.getScheduleToStartTimeout();
      if (scheduleToStartTimeout != null) {
        long scheduleToStartTimeoutMs = scheduleToStartTimeout.toMillis();
        if (scheduleToStartTimeoutMs > 0 && scheduleToStartTimeoutMs < acceptanceTimeoutMs) {
          acceptanceTimeoutMs = scheduleToStartTimeoutMs;
          timeoutIsScheduleToStart = true;
        }
      }
      try {
        SlotPermit permit = null;
        SlotReservationData reservationCtx =
            new SlotReservationData(taskQueue, options.getIdentity(), options.getBuildId());
        if (acceptanceTimeoutMs <= 0) {
          permit = slotSupplier.reserveSlot(reservationCtx);
        } else {
          try {
            TimeLimiter timeLimiter = SimpleTimeLimiter.create(timeoutThreadPool);
            permit =
                timeLimiter.callWithTimeout(
                    () -> slotSupplier.reserveSlot(reservationCtx),
                    acceptanceTimeoutMs,
                    TimeUnit.MILLISECONDS);
          } catch (TimeoutException e) {
            // In the event that we timed out waiting for a permit *because of schedule to start* we
            // still want to proceed with the "attempt" with a null permit, which will then
            // immediately fail with the s2s timeout.
            if (!timeoutIsScheduleToStart) {
              log.warn(
                  "LocalActivity queue is full and submitting timed out for activity {} with acceptanceTimeoutMs: {}",
                  activityTask.getActivityId(),
                  acceptanceTimeoutMs);
              return false;
            }
          }
        }

        executionContext.setPermit(permit);
        // we should publish scheduleToClose before submission, so the handlers always see a full
        // state of executionContext
        @Nullable Deadline scheduleToCloseDeadline = executionContext.getScheduleToCloseDeadline();
        if (scheduleToCloseDeadline != null) {
          ScheduledFuture<?> scheduleToCloseFuture =
              scheduledExecutor.schedule(
                  new FinalTimeoutHandler(
                      TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, executionContext),
                  scheduleToCloseDeadline.timeRemaining(TimeUnit.MILLISECONDS),
                  TimeUnit.MILLISECONDS);
          executionContext.setScheduleToCloseFuture(scheduleToCloseFuture);
        }
        submitAttempt(executionContext, activityTask);
        log.trace("LocalActivity queued: {}", activityTask.getActivityId());
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception e) {
        log.warn("Error while trying to reserve a slot for local activity", e.getCause());
        return false;
      }
    }

    /**
     * @param attemptTask local activity retry attempt task specifying the retry we are about to
     *     schedule
     * @return true if the retry attempt specified by {@code task} is not allowed by the current
     *     retry policy and the error was submitted in the callback, false otherwise
     */
    private boolean failIfRetryIsNotAllowedByNewPolicy(
        LocalActivityExecutionContext executionContext,
        PollActivityTaskQueueResponseOrBuilder attemptTask) {
      final Failure previousExecutionFailure = executionContext.getPreviousExecutionFailure();
      if (previousExecutionFailure != null) {
        // This is not an original local execution, it's a continuation using a workflow timer.
        // We should verify if the RetryOptions currently supplied in the workflow still allow the
        // retry.
        // If not, we need to recreate the same structure of an error like it would happen before we
        // started to sleep on the timer, at the end of the previous local execution.
        RetryState retryState =
            shouldStillRetry(executionContext, attemptTask, previousExecutionFailure);
        if (!RetryState.RETRY_STATE_IN_PROGRESS.equals(retryState)) {
          Failure failure;
          if (RetryState.RETRY_STATE_TIMEOUT.equals(retryState)) {
            if (previousExecutionFailure.hasTimeoutFailureInfo()
                && TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE.equals(
                    previousExecutionFailure.getTimeoutFailureInfo().getTimeoutType())) {
              // This scenario should behave the same way as a startToClose timeout happening and
              // encountering
              // RetryState#TIMEOUT during calculation of the next attempt (which is effectively a
              // scheduleToClose
              // timeout).
              // See how StartToCloseTimeoutHandler or
              // io.temporal.internal.testservice.StateMachines#timeoutActivityTask
              // discard startToClose in this case and replaces it with scheduleToClose
              failure =
                  newTimeoutFailure(
                      TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
                      previousExecutionFailure.getCause());
            } else {
              failure =
                  newTimeoutFailure(
                      TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, previousExecutionFailure);
            }
          } else {
            failure = previousExecutionFailure;
          }

          executionContext.callback(
              failed(
                  executionContext.getActivityId(),
                  attemptTask.getAttempt(),
                  retryState,
                  failure,
                  null));
          return true;
        }
      }
      return false;
    }

    /**
     * @param executionContext execution context of the activity
     * @param activityTask activity task
     * @param previousLocalExecutionFailure failure happened during previous local activity
     *     execution. Can be null.
     * @return decision to retry or not with a retry state, backoff or delay to the next attempt if
     *     applicable
     */
    @Nonnull
    private RetryState shouldStillRetry(
        LocalActivityExecutionContext executionContext,
        PollActivityTaskQueueResponseOrBuilder activityTask,
        @Nullable Failure previousLocalExecutionFailure) {
      int currentAttempt = activityTask.getAttempt();

      if (isRetryPolicyNotSet(activityTask)) {
        return RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET;
      }

      RetryOptions retryOptions = RetryOptionsUtils.toRetryOptions(activityTask.getRetryPolicy());

      if (previousLocalExecutionFailure != null
          && previousLocalExecutionFailure.hasApplicationFailureInfo()
          && RetryOptionsUtils.isNotRetryable(
              retryOptions, previousLocalExecutionFailure.getApplicationFailureInfo().getType())) {
        return RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE;
      }

      // The current attempt didn't happen yet in this check, that's why -1
      if (RetryOptionsUtils.areAttemptsReached(retryOptions, currentAttempt - 1)) {
        return RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED;
      }

      long sleepMillis = retryOptions.calculateSleepTime(currentAttempt);
      if (RetryOptionsUtils.isDeadlineReached(
          executionContext.getScheduleToCloseDeadline(), sleepMillis)) {
        return RetryState.RETRY_STATE_TIMEOUT;
      }

      return RetryState.RETRY_STATE_IN_PROGRESS;
    }
  }

  private class AttemptTaskHandlerImpl
      implements PollTaskExecutor.TaskHandler<LocalActivityAttemptTask> {

    private final ActivityTaskHandler handler;

    private AttemptTaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(LocalActivityAttemptTask attemptTask) throws Exception {
      // cancel scheduleToStart timeout if not already fired
      @Nullable ScheduledFuture<?> scheduleToStartFuture = attemptTask.getScheduleToStartFuture();
      boolean scheduleToStartFired =
          scheduleToStartFuture != null && !scheduleToStartFuture.cancel(false);

      LocalActivityExecutionContext executionContext = attemptTask.getExecutionContext();
      executionContext.newAttempt();
      PollActivityTaskQueueResponseOrBuilder activityTask = attemptTask.getAttemptTask();

      // if an activity was already completed by any mean like scheduleToClose or scheduleToStart,
      // discard this attempt, this execution is completed.
      // The scheduleToStartFired check here is a bit overkill, but allows to catch an edge case
      // where
      // scheduleToStart is already fired, but didn't report a completion yet.
      boolean shouldDiscardTheAttempt = scheduleToStartFired || executionContext.isCompleted();
      if (shouldDiscardTheAttempt) {
        return;
      }

      Scope metricsScope =
          workerMetricsScope.tagged(
              ImmutableMap.of(
                  MetricsTag.ACTIVITY_TYPE,
                  activityTask.getActivityType().getName(),
                  MetricsTag.WORKFLOW_TYPE,
                  activityTask.getWorkflowType().getName()));

      MDC.put(LoggerTag.ACTIVITY_ID, activityTask.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, activityTask.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, activityTask.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, activityTask.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, activityTask.getWorkflowExecution().getRunId());

      // At this point the permit should definitely be set
      if (executionContext.getPermit() == null) {
        throw new IllegalStateException("Permit is expected to be set at this point");
      }

      slotSupplier.markSlotUsed(
          new LocalActivitySlotInfo(
              ActivityPollResponseToInfo.toActivityInfoImpl(
                  activityTask, namespace, taskQueue, true),
              options.getIdentity(),
              options.getBuildId()),
          executionContext.getPermit());

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
              handler.handle(
                  new ActivityTask(activityTask, executionContext.getPermit(), () -> {}),
                  metricsScope,
                  true);
        } finally {
          sw.stop();
        }

        // Cancel startToCloseTimeoutFuture if it's not yet fired.
        boolean startToCloseTimeoutFired =
            startToCloseTimeoutFuture != null && !startToCloseTimeoutFuture.cancel(false);

        // We make sure that the result handling code following this statement is mutual exclusive
        // with the startToClose timeout handler.
        // If startToClose fired, scheduling of the next attempt is taken care by the
        // StartToCloseTimeoutHandler.
        // If execution is already completed, this attempt handling shouldn't proceed, nothing to do
        // with result. The typical scenario may be fired scheduleToClose.
        boolean shouldDiscardTheResult = startToCloseTimeoutFired || executionContext.isCompleted();
        if (shouldDiscardTheResult) {
          return;
        }

        handleResult(activityHandlerResult, attemptTask, metricsScope);
      } catch (Throwable ex) {
        // handleLocalActivity is expected to never throw an exception and return a result
        // that can be used for a workflow callback if this method throws, it's a bug.
        log.error("[BUG] Code that expected to never throw an exception threw an exception", ex);
        executionContext.callback(
            processingFailed(activityTask.getActivityId(), activityTask.getAttempt(), ex));
        throw ex;
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);
      }
    }

    private void handleResult(
        ActivityTaskHandler.Result activityHandlerResult,
        LocalActivityAttemptTask attemptTask,
        Scope metricsScope) {
      LocalActivityExecutionContext executionContext = attemptTask.getExecutionContext();
      PollActivityTaskQueueResponseOrBuilder activityTask = attemptTask.getAttemptTask();
      int currentAttempt = activityTask.getAttempt();

      // Success
      if (activityHandlerResult.getTaskCompleted() != null) {
        boolean completedByThisInvocation =
            executionContext.callback(
                LocalActivityResult.completed(activityHandlerResult, currentAttempt));
        if (completedByThisInvocation) {
          // We report this metric only if the execution was completed by us right now, not by any
          // timeout earlier.
          // Completion by another attempt is not possible by another attempt earlier where we
          // checked if startToClose fired.
          com.uber.m3.util.Duration e2eDuration =
              com.uber.m3.util.Duration.ofMillis(
                  System.currentTimeMillis() - executionContext.getOriginalScheduledTimestamp());
          metricsScope.timer(MetricsType.LOCAL_ACTIVITY_SUCCEED_E2E_LATENCY).record(e2eDuration);
        }
        return;
      }

      // Cancellation
      if (activityHandlerResult.getTaskCanceled() != null) {
        executionContext.callback(
            LocalActivityResult.cancelled(activityHandlerResult, currentAttempt));
        return;
      }

      // Failure
      Preconditions.checkState(
          activityHandlerResult.getTaskFailed() != null,
          "One of taskCompleted, taskCanceled or taskFailed must be set");

      Failure executionFailure =
          activityHandlerResult.getTaskFailed().getTaskFailedRequest().getFailure();
      Throwable executionThrowable = activityHandlerResult.getTaskFailed().getFailure();

      RetryDecision retryDecision =
          shouldRetry(
              executionContext, activityTask, activityHandlerResult.getTaskFailed().getFailure());

      if (retryDecision.doNextAttempt()) {
        scheduleNextAttempt(
            executionContext,
            Objects.requireNonNull(
                retryDecision.nextAttemptBackoff, "nextAttemptBackoff is expected to not be null"),
            executionFailure);
      } else if (retryDecision.failWorkflowTask()) {
        executionContext.callback(
            processingFailed(executionContext.getActivityId(), currentAttempt, executionThrowable));
      } else {
        executionContext.callback(
            failed(
                executionContext.getActivityId(),
                currentAttempt,
                retryDecision.retryState,
                executionFailure,
                retryDecision.nextAttemptBackoff));
      }
    }

    @Override
    public Throwable wrapFailure(LocalActivityAttemptTask task, Throwable failure) {
      return new RuntimeException("Failure processing local activity task.", failure);
    }
  }

  private class LocalActivityRetryHandler implements Runnable {
    private final @Nonnull LocalActivityExecutionContext executionContext;
    private final @Nonnull PollActivityTaskQueueResponse.Builder activityTask;

    private LocalActivityRetryHandler(
        @Nonnull LocalActivityExecutionContext executionContext,
        @Nonnull PollActivityTaskQueueResponse.Builder activityTask) {
      this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
      this.activityTask = Objects.requireNonNull(activityTask, "activityTask");
    }

    @Override
    public void run() {
      submitRetry(executionContext, activityTask);
    }
  }

  /** Used to perform both scheduleToStart and scheduleToClose timeouts. */
  private static class FinalTimeoutHandler implements Runnable {
    private final LocalActivityExecutionContext executionContext;
    private final TimeoutType timeoutType;

    public FinalTimeoutHandler(
        TimeoutType timeoutType, LocalActivityExecutionContext executionContext) {
      this.executionContext = executionContext;
      this.timeoutType = timeoutType;
    }

    @Override
    public void run() {
      executionContext.callback(
          failed(
              executionContext.getActivityId(),
              executionContext.getCurrentAttempt(),
              RetryState.RETRY_STATE_TIMEOUT,
              newTimeoutFailure(timeoutType, executionContext.getLastAttemptFailure()),
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
      PollActivityTaskQueueResponseOrBuilder activityTask = attemptTask.getAttemptTask();
      String activityId = activityTask.getActivityId();

      int timingOutAttempt = activityTask.getAttempt();

      RetryDecision retryDecision = shouldRetry(executionContext, activityTask, null);
      if (retryDecision.doNextAttempt()) {
        scheduleNextAttempt(
            executionContext,
            Objects.requireNonNull(
                retryDecision.nextAttemptBackoff, "nextAttemptBackoff is expected to not be null"),
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
                timingOutAttempt,
                retryDecision.retryState,
                newTimeoutFailure(timeoutType, executionContext.getLastAttemptFailure()),
                retryDecision.nextAttemptBackoff));
      }
    }
  }

  @Override
  public boolean start() {
    if (handler.isAnyTypeSupported()) {
      this.scheduledExecutor =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread thread = new Thread(r);
                thread.setName(
                    WorkerThreadsNameHelper.getLocalActivitySchedulerThreadPrefix(
                        namespace, taskQueue));
                return thread;
              });

      this.activityAttemptTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new AttemptTaskHandlerImpl(handler),
              pollerOptions,
              slotSupplier.maximumSlots(),
              false);

      this.workerMetricsScope.counter(MetricsType.WORKER_START_COUNTER).inc(1);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    if (activityAttemptTaskExecutor != null && !activityAttemptTaskExecutor.isShutdown()) {
      return activityAttemptTaskExecutor
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
    } else {
      return CompletableFuture.completedFuture(null);
    }
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    ShutdownManager.awaitTermination(scheduledExecutor, timeoutMillis);
  }

  @Override
  public boolean isShutdown() {
    return activityAttemptTaskExecutor != null && activityAttemptTaskExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return activityAttemptTaskExecutor != null
        && activityAttemptTaskExecutor.isTerminated()
        && scheduledExecutor.isTerminated();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    if (activityAttemptTaskExecutor == null) {
      return WorkerLifecycleState.NOT_STARTED;
    }
    if (activityAttemptTaskExecutor.isShutdown()) {
      // return TERMINATED only if both pollExecutor and taskExecutor are terminated
      if (activityAttemptTaskExecutor.isTerminated() && scheduledExecutor.isTerminated()) {
        return WorkerLifecycleState.TERMINATED;
      } else {
        return WorkerLifecycleState.SHUTDOWN;
      }
    }
    return WorkerLifecycleState.ACTIVE;
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

  private static Optional<Duration> getNextRetryDelay(@Nullable Throwable executionThrowable) {
    if (executionThrowable instanceof ApplicationFailure) {
      return Optional.ofNullable(((ApplicationFailure) executionThrowable).getNextRetryDelay());
    }
    return Optional.empty();
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

    public boolean failWorkflowTask() {
      return RetryState.RETRY_STATE_INTERNAL_SERVER_ERROR.equals(retryState);
    }
  }
}
