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

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributesOrBuilder;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.worker.tuning.*;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class ActivityWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

  private SuspendableWorker poller = new NoopWorker();
  private PollTaskExecutor<ActivityTask> pollTaskExecutor;

  private final ActivityTaskHandler handler;
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final double taskQueueActivitiesPerSecond;
  private final PollerOptions pollerOptions;
  private final Scope workerMetricsScope;
  private final GrpcRetryer grpcRetryer;
  private final GrpcRetryer.GrpcRetryerOptions replyGrpcRetryerOptions;
  private final TrackingSlotSupplier<ActivitySlotInfo> slotSupplier;

  public ActivityWorker(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      double taskQueueActivitiesPerSecond,
      @Nonnull SingleWorkerOptions options,
      @Nonnull ActivityTaskHandler handler,
      @Nonnull TrackingSlotSupplier<ActivitySlotInfo> slotSupplier) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.handler = Objects.requireNonNull(handler);
    this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
    this.options = Objects.requireNonNull(options);
    this.pollerOptions = getPollerOptions(options);
    this.workerMetricsScope =
        MetricsTag.tagged(options.getMetricsScope(), WorkerMetricsTag.WorkerType.ACTIVITY_WORKER);
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.replyGrpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(
            DefaultStubServiceOperationRpcRetryOptions.INSTANCE, null);
    this.slotSupplier = slotSupplier;
    this.slotSupplier.setMetricsScope(this.workerMetricsScope);
  }

  @Override
  public boolean start() {
    if (handler.isAnyTypeSupported()) {
      this.pollTaskExecutor =
          new PollTaskExecutor<>(
              namespace,
              taskQueue,
              options.getIdentity(),
              new TaskHandlerImpl(handler),
              pollerOptions,
              slotSupplier.maximumSlots(),
              true);
      poller =
          new Poller<>(
              options.getIdentity(),
              new ActivityPollTask(
                  service,
                  namespace,
                  taskQueue,
                  options.getIdentity(),
                  options.getBuildId(),
                  options.isUsingBuildIdForVersioning(),
                  taskQueueActivitiesPerSecond,
                  this.slotSupplier,
                  workerMetricsScope,
                  service.getServerCapabilities()),
              this.pollTaskExecutor,
              pollerOptions,
              workerMetricsScope);
      poller.start();
      workerMetricsScope.counter(MetricsType.WORKER_START_COUNTER).inc(1);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    String supplierName = this + "#executorSlots";
    return poller
        .shutdown(shutdownManager, interruptTasks)
        .thenCompose(
            ignore ->
                !interruptTasks
                    ? shutdownManager.waitForSupplierPermitsReleasedUnlimited(
                        slotSupplier, supplierName)
                    : CompletableFuture.completedFuture(null))
        .thenCompose(
            ignore ->
                pollTaskExecutor != null
                    ? pollTaskExecutor.shutdown(shutdownManager, interruptTasks)
                    : CompletableFuture.completedFuture(null))
        .exceptionally(
            e -> {
              log.error("Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = ShutdownManager.awaitTermination(poller, unit.toMillis(timeout));
    // relies on the fact that the pollTaskExecutor is the last one to be shutdown, no need to
    // wait separately for intermediate steps
    ShutdownManager.awaitTermination(pollTaskExecutor, timeoutMillis);
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
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated() && (pollTaskExecutor == null || pollTaskExecutor.isTerminated());
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return poller.getLifecycleState();
  }

  public EagerActivityDispatcher getEagerActivityDispatcher() {
    return new EagerActivityDispatcherImpl();
  }

  private PollerOptions getPollerOptions(SingleWorkerOptions options) {
    PollerOptions pollerOptions = options.getPollerOptions();
    if (pollerOptions.getPollThreadNamePrefix() == null) {
      pollerOptions =
          PollerOptions.newBuilder(pollerOptions)
              .setPollThreadNamePrefix(
                  WorkerThreadsNameHelper.getActivityPollerThreadPrefix(namespace, taskQueue))
              .build();
    }
    return pollerOptions;
  }

  @Override
  public String toString() {
    return String.format(
        "ActivityWorker{identity=%s, namespace=%s, taskQueue=%s}",
        options.getIdentity(), namespace, taskQueue);
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<ActivityTask> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(ActivityTask task) throws Exception {
      PollActivityTaskQueueResponseOrBuilder pollResponse = task.getResponse();
      Scope metricsScope =
          workerMetricsScope.tagged(
              ImmutableMap.of(
                  MetricsTag.ACTIVITY_TYPE,
                  pollResponse.getActivityType().getName(),
                  MetricsTag.WORKFLOW_TYPE,
                  pollResponse.getWorkflowType().getName()));

      MDC.put(LoggerTag.ACTIVITY_ID, pollResponse.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, pollResponse.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, pollResponse.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, pollResponse.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, pollResponse.getWorkflowExecution().getRunId());

      ActivityTaskHandler.Result result = null;
      try {
        result = handleActivity(task, metricsScope);
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.WORKFLOW_TYPE);
        MDC.remove(LoggerTag.RUN_ID);
        if (
        // handleActivity throw an exception (not a normal scenario)
        result == null
            // completed synchronously or manual completion hasn't been requested
            || !result.isManualCompletion()) {
          task.getCompletionCallback().apply();
        }
      }

      if (result.getTaskFailed() != null && result.getTaskFailed().getFailure() instanceof Error) {
        // don't just swallow Errors, we need to propagate it to the top
        throw (Error) result.getTaskFailed().getFailure();
      }
    }

    private ActivityTaskHandler.Result handleActivity(ActivityTask task, Scope metricsScope) {
      PollActivityTaskQueueResponseOrBuilder pollResponse = task.getResponse();
      ByteString taskToken = pollResponse.getTaskToken();
      metricsScope
          .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
          .record(
              ProtobufTimeUtils.toM3Duration(
                  pollResponse.getStartedTime(), pollResponse.getCurrentAttemptScheduledTime()));

      ActivityTaskHandler.Result result;

      Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_EXEC_LATENCY).start();
      try {
        result = handler.handle(task, metricsScope, false);
      } catch (Throwable ex) {
        // handler.handle if expected to never throw an exception and return result
        // that can be used for a workflow callback if this method throws, it's a bug.
        log.error("[BUG] Code that expected to never throw an exception threw an exception", ex);
        throw ex;
      } finally {
        sw.stop();
      }

      try {
        sendReply(taskToken, result, metricsScope);
      } catch (Exception e) {
        logExceptionDuringResultReporting(e, pollResponse, result);
        // TODO this class doesn't report activity success and failure metrics now, instead it's
        //  located inside an activity handler. We should lift it up to this level,
        //  so we can increment a failure counter instead of success if send result failed.
        //  This will also align the behavior of ActivityWorker with WorkflowWorker.
        throw e;
      }

      if (result.getTaskCompleted() != null) {
        Duration e2eDuration =
            ProtobufTimeUtils.toM3DurationSinceNow(pollResponse.getScheduledTime());
        metricsScope.timer(MetricsType.ACTIVITY_SUCCEED_E2E_LATENCY).record(e2eDuration);
      }

      return result;
    }

    @Override
    public Throwable wrapFailure(ActivityTask t, Throwable failure) {
      PollActivityTaskQueueResponseOrBuilder response = t.getResponse();
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
        ByteString taskToken, ActivityTaskHandler.Result response, Scope metricsScope) {
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        RespondActivityTaskCompletedRequest request =
            taskCompleted.toBuilder()
                .setTaskToken(taskToken)
                .setIdentity(options.getIdentity())
                .setNamespace(namespace)
                .setWorkerVersion(options.workerVersionStamp())
                .build();

        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompleted(request),
            replyGrpcRetryerOptions);
      } else {
        Result.TaskFailedResult taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          RespondActivityTaskFailedRequest request =
              taskFailed.getTaskFailedRequest().toBuilder()
                  .setTaskToken(taskToken)
                  .setIdentity(options.getIdentity())
                  .setNamespace(namespace)
                  .setWorkerVersion(options.workerVersionStamp())
                  .build();

          grpcRetryer.retry(
              () ->
                  service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                      .respondActivityTaskFailed(request),
              replyGrpcRetryerOptions);
        } else {
          RespondActivityTaskCanceledRequest taskCanceled = response.getTaskCanceled();
          if (taskCanceled != null) {
            RespondActivityTaskCanceledRequest request =
                taskCanceled.toBuilder()
                    .setTaskToken(taskToken)
                    .setIdentity(options.getIdentity())
                    .setNamespace(namespace)
                    .setWorkerVersion(options.workerVersionStamp())
                    .build();

            grpcRetryer.retry(
                () ->
                    service
                        .blockingStub()
                        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                        .respondActivityTaskCanceled(request),
                replyGrpcRetryerOptions);
          }
        }
      }
      // Manual activity completion
    }

    private void logExceptionDuringResultReporting(
        Exception e,
        PollActivityTaskQueueResponseOrBuilder pollResponse,
        ActivityTaskHandler.Result result) {
      MDC.put(LoggerTag.ACTIVITY_ID, pollResponse.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, pollResponse.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, pollResponse.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.RUN_ID, pollResponse.getWorkflowExecution().getRunId());

      if (log.isDebugEnabled()) {
        log.debug(
            "Failure during reporting of activity result to the server. ActivityId = {}, ActivityType = {}, WorkflowId={}, WorkflowType={}, RunId={}, ActivityResult={}",
            pollResponse.getActivityId(),
            pollResponse.getActivityType().getName(),
            pollResponse.getWorkflowExecution().getWorkflowId(),
            pollResponse.getWorkflowType().getName(),
            pollResponse.getWorkflowExecution().getRunId(),
            result,
            e);
      } else {
        log.warn(
            "Failure during reporting of activity result to the server. ActivityId = {}, ActivityType = {}, WorkflowId={}, WorkflowType={}, RunId={}",
            pollResponse.getActivityId(),
            pollResponse.getActivityType().getName(),
            pollResponse.getWorkflowExecution().getWorkflowId(),
            pollResponse.getWorkflowType().getName(),
            pollResponse.getWorkflowExecution().getRunId(),
            e);
      }
    }
  }

  private final class EagerActivityDispatcherImpl implements EagerActivityDispatcher {
    @Override
    public Optional<SlotPermit> tryReserveActivitySlot(
        ScheduleActivityTaskCommandAttributesOrBuilder commandAttributes) {
      if (!WorkerLifecycleState.ACTIVE.equals(ActivityWorker.this.getLifecycleState())
          || !Objects.equals(
              commandAttributes.getTaskQueue().getName(), ActivityWorker.this.taskQueue)) {
        return Optional.empty();
      }
      return ActivityWorker.this.slotSupplier.tryReserveSlot(
          new SlotReservationData(ActivityWorker.this.taskQueue));
    }

    @Override
    public void releaseActivitySlotReservations(Iterable<SlotPermit> permits) {
      for (SlotPermit permit : permits) {
        ActivityWorker.this.slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
      }
    }

    @Override
    public void dispatchActivity(PollActivityTaskQueueResponse activity, SlotPermit permit) {
      ActivityWorker.this.pollTaskExecutor.process(
          new ActivityTask(
              activity,
              () ->
                  ActivityWorker.this.slotSupplier.releaseSlot(
                      SlotReleaseReason.taskComplete(), permit)));
    }
  }
}
