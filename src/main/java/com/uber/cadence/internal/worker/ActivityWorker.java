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

import com.uber.cadence.*;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.Retryer;
import com.uber.cadence.internal.logging.LoggerTag;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.worker.ActivityTaskHandler.Result;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.apache.thrift.TException;
import org.slf4j.MDC;

public final class ActivityWorker implements SuspendableWorker {

  private static final String POLL_THREAD_NAME_PREFIX = "Activity Poller taskList=";

  private SuspendableWorker poller = new NoopSuspendableWorker();
  private final ActivityTaskHandler handler;
  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final SingleWorkerOptions options;

  public ActivityWorker(
      IWorkflowService service,
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

  static class MeasurableActivityTask {
    PollForActivityTaskResponse task;
    Stopwatch sw;

    MeasurableActivityTask(PollForActivityTaskResponse task, Stopwatch sw) {
      this.task = Objects.requireNonNull(task);
      this.sw = Objects.requireNonNull(sw);
    }

    void markDone() {
      sw.stop();
    }
  }

  private class TaskHandlerImpl implements PollTaskExecutor.TaskHandler<MeasurableActivityTask> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(MeasurableActivityTask task) throws Exception {
      Scope metricsScope =
          options
              .getMetricsScope()
              .tagged(
                  ImmutableMap.of(MetricsTag.ACTIVITY_TYPE, task.task.getActivityType().getName()));
      metricsScope
          .timer(MetricsType.TASK_LIST_QUEUE_LATENCY)
          .record(
              Duration.ofNanos(
                  task.task.getStartedTimestamp() - task.task.getScheduledTimestamp()));

      // The following tags are for logging.
      MDC.put(LoggerTag.ACTIVITY_ID, task.task.getActivityId());
      MDC.put(LoggerTag.ACTIVITY_TYPE, task.task.getActivityType().getName());
      MDC.put(LoggerTag.WORKFLOW_ID, task.task.getWorkflowExecution().getWorkflowId());
      MDC.put(LoggerTag.RUN_ID, task.task.getWorkflowExecution().getRunId());

      try {
        Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_EXEC_LATENCY).start();
        ActivityTaskHandler.Result response = handler.handle(task.task, metricsScope, false);
        sw.stop();

        sw = metricsScope.timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
        sendReply(task.task, response, metricsScope);
        sw.stop();

        task.markDone();
      } catch (CancellationException e) {
        RespondActivityTaskCanceledRequest cancelledRequest =
            new RespondActivityTaskCanceledRequest();
        cancelledRequest.setDetails(
            String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        Stopwatch sw = metricsScope.timer(MetricsType.ACTIVITY_RESP_LATENCY).start();
        sendReply(task.task, new Result(null, null, cancelledRequest, null), metricsScope);
        sw.stop();
      } finally {
        MDC.remove(LoggerTag.ACTIVITY_ID);
        MDC.remove(LoggerTag.ACTIVITY_TYPE);
        MDC.remove(LoggerTag.WORKFLOW_ID);
        MDC.remove(LoggerTag.RUN_ID);
      }
    }

    @Override
    public Throwable wrapFailure(MeasurableActivityTask task, Throwable failure) {
      WorkflowExecution execution = task.task.getWorkflowExecution();
      return new RuntimeException(
          "Failure processing activity task. WorkflowID="
              + execution.getWorkflowId()
              + ", RunID="
              + execution.getRunId()
              + ", ActivityType="
              + task.task.getActivityType().getName()
              + ", ActivityID="
              + task.task.getActivityId(),
          failure);
    }

    private void sendReply(
        PollForActivityTaskResponse task, ActivityTaskHandler.Result response, Scope metricsScope)
        throws TException {
      RetryOptions ro = response.getRequestRetryOptions();
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro =
            options
                .getReportCompletionRetryOptions()
                .merge(ro)
                .addDoNotRetry(
                    BadRequestError.class, EntityNotExistsError.class, DomainNotActiveError.class);
        taskCompleted.setTaskToken(task.getTaskToken());
        taskCompleted.setIdentity(options.getIdentity());
        Retryer.retry(ro, () -> service.RespondActivityTaskCompleted(taskCompleted));
        metricsScope.counter(MetricsType.ACTIVITY_TASK_COMPLETED_COUNTER).inc(1);
      } else {
        if (response.getTaskFailedResult() != null) {
          RespondActivityTaskFailedRequest taskFailed =
              response.getTaskFailedResult().getTaskFailedRequest();
          ro =
              options
                  .getReportFailureRetryOptions()
                  .merge(ro)
                  .addDoNotRetry(
                      BadRequestError.class,
                      EntityNotExistsError.class,
                      DomainNotActiveError.class);
          taskFailed.setTaskToken(task.getTaskToken());
          taskFailed.setIdentity(options.getIdentity());
          Retryer.retry(ro, () -> service.RespondActivityTaskFailed(taskFailed));
          metricsScope.counter(MetricsType.ACTIVITY_TASK_FAILED_COUNTER).inc(1);
        } else {
          RespondActivityTaskCanceledRequest taskCancelled = response.getTaskCancelled();
          if (taskCancelled != null) {
            taskCancelled.setTaskToken(task.getTaskToken());
            taskCancelled.setIdentity(options.getIdentity());
            ro =
                options
                    .getReportFailureRetryOptions()
                    .merge(ro)
                    .addDoNotRetry(
                        BadRequestError.class,
                        EntityNotExistsError.class,
                        DomainNotActiveError.class);
            Retryer.retry(ro, () -> service.RespondActivityTaskCanceled(taskCancelled));
            metricsScope.counter(MetricsType.ACTIVITY_TASK_CANCELED_COUNTER).inc(1);
          }
        }
      }
      // Manual activity completion
    }
  }
}
