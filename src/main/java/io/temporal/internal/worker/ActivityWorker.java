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

package io.temporal.internal.worker;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.context.ContextPropagator;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.serviceclient.GrpcRetryOptions;
import io.temporal.serviceclient.GrpcRetryer;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

public final class ActivityWorker implements SuspendableWorker {

  private static final String POLL_THREAD_NAME_PREFIX = "Activity Poller taskList=";

  private SuspendableWorker poller = new NoopSuspendableWorker();
  private final ActivityTaskHandler handler;
  private final GrpcWorkflowServiceFactory service;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;

  public ActivityWorker(
      GrpcWorkflowServiceFactory service,
      String domain,
      String taskList,
      SingleWorkerOptions options,
      ActivityTaskHandler handler) {
    this.service = Objects.requireNonNull(service);
    this.domain = Objects.requireNonNull(domain);
    this.taskList = Objects.requireNonNull(taskList);
    this.handler = handler;

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
              new ActivityPollTask(service, domain, taskList, options),
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

        metricsScope
            .timer(MetricsType.ACTIVITY_E2E_LATENCY)
            .record(
                Duration.ofNanos(System.nanoTime() - task.getScheduledTimestampOfThisAttempt()));

      } catch (CancellationException e) {
        RespondActivityTaskCanceledRequest cancelledRequest =
            RespondActivityTaskCanceledRequest.newBuilder()
                .setDetails(ByteString.copyFrom(e.getMessage(), StandardCharsets.UTF_8))
                .build();
        Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
        sendReply(task, new Result(null, null, cancelledRequest, null), metricsScope);
        sw.stop();
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
      Map<String, byte[]> headerData = new HashMap<>();
      for (Map.Entry<String, ByteString> entry : response.getHeader().getFieldsMap().entrySet()) {
        headerData.put(entry.getKey(), entry.getValue().toByteArray());
      }
      for (ContextPropagator propagator : options.getContextPropagators()) {
        propagator.setCurrentContext(propagator.deserializeContext(headerData));
      }
    }

    @Override
    public Throwable wrapFailure(PollForActivityTaskResponse task, Throwable failure) {
      WorkflowExecution execution = task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing activity task. WorkflowID="
              + execution.getWorkflowId()
              + ", RunID="
              + execution.getRunId()
              + ", ActivityType="
              + task.getActivityType().getName()
              + ", ActivityID="
              + task.getActivityId(),
          failure);
    }

    private void sendReply(
        PollForActivityTaskResponse task, ActivityTaskHandler.Result response, Scope metricsScope) {
      GrpcRetryOptions ro = response.getRequestRetryOptions();
      RespondActivityTaskCompletedRequest.Builder taskCompleted =
          response.getTaskCompleted().toBuilder();
      if (taskCompleted != null) {
        ro = options.getReportCompletionRetryOptions().merge(ro);
        taskCompleted.setTaskToken(task.getTaskToken());
        taskCompleted.setIdentity(options.getIdentity());
        GrpcRetryer.retry(
            ro, () -> service.blockingStub().respondActivityTaskCompleted(taskCompleted.build()));
        metricsScope.counter(MetricsType.ACTIVITY_TASK_COMPLETED_COUNTER).inc(1);
      } else {
        if (response.getTaskFailedResult() != null) {
          RespondActivityTaskFailedRequest.Builder taskFailed =
              response.getTaskFailedResult().getTaskFailedRequest().toBuilder();
          ro = options.getReportFailureRetryOptions().merge(ro);
          taskFailed.setTaskToken(task.getTaskToken());
          taskFailed.setIdentity(options.getIdentity());
          GrpcRetryer.retry(
              ro, () -> service.blockingStub().respondActivityTaskFailed(taskFailed.build()));
          metricsScope.counter(MetricsType.ACTIVITY_TASK_FAILED_COUNTER).inc(1);
        } else {
          RespondActivityTaskCanceledRequest.Builder taskCancelled =
              response.getTaskCancelled().toBuilder();
          if (taskCancelled != null) {
            taskCancelled.setTaskToken(task.getTaskToken());
            taskCancelled.setIdentity(options.getIdentity());
            ro = options.getReportFailureRetryOptions().merge(ro);
            GrpcRetryer.retry(
                ro,
                () -> service.blockingStub().respondActivityTaskCanceled(taskCancelled.build()));
            metricsScope.counter(MetricsType.ACTIVITY_TASK_CANCELED_COUNTER).inc(1);
          }
        }
      }
      // Manual activity completion
    }
  }
}
