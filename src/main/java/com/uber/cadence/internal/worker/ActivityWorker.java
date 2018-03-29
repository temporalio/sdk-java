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

import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.Retryer;
import com.uber.cadence.internal.worker.ActivityTaskHandler.Result;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ActivityWorker implements SuspendableWorker {

  private static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

  private static final String POLL_THREAD_NAME_PREFIX = "Activity Poller ";

  private Poller poller;
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
    Objects.requireNonNull(service);
    Objects.requireNonNull(domain);
    Objects.requireNonNull(taskList);
    this.service = service;
    this.domain = domain;
    this.taskList = taskList;
    this.options = options;
    this.handler = handler;
  }

  @Override
  public void start() {
    if (handler.isAnyTypeSupported()) {
      PollerOptions pollerOptions = options.getPollerOptions();
      if (pollerOptions.getPollThreadNamePrefix() == null) {
        pollerOptions =
            new PollerOptions.Builder(pollerOptions)
                .setPollThreadNamePrefix(
                    POLL_THREAD_NAME_PREFIX
                        + "\""
                        + taskList
                        + "\", domain=\""
                        + domain
                        + "\", type=\"activity\"")
                .build();
      }
      Poller.ThrowingRunnable pollTask =
          new PollTask<>(service, domain, taskList, options, new TaskHandlerImpl(handler));
      new PollTask<>(service, domain, taskList, options, new TaskHandlerImpl(handler));
      poller = new Poller(pollerOptions, options.getIdentity(), pollTask);
      poller.start();
    }
  }

  @Override
  public void shutdown() {
    if (poller != null) {
      poller.shutdown();
    }
  }

  @Override
  public void shutdownNow() {
    if (poller != null) {
      poller.shutdownNow();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    if (poller == null) {
      return true;
    }
    return poller.awaitTermination(timeout, unit);
  }

  @Override
  public boolean shutdownAndAwaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    if (poller == null) {
      return true;
    }
    return poller.shutdownAndAwaitTermination(timeout, unit);
  }

  @Override
  public boolean isRunning() {
    if (poller == null) {
      return false;
    }
    return poller.isRunning();
  }

  @Override
  public void suspendPolling() {
    if (poller != null) {
      poller.suspendPolling();
    }
  }

  @Override
  public void resumePolling() {
    if (poller != null) {
      poller.resumePolling();
    }
  }

  private class TaskHandlerImpl implements PollTask.TaskHandler<PollForActivityTaskResponse> {

    final ActivityTaskHandler handler;

    private TaskHandlerImpl(ActivityTaskHandler handler) {
      this.handler = handler;
    }

    @Override
    public void handle(
        IWorkflowService service, String domain, String taskList, PollForActivityTaskResponse task)
        throws Exception {
      try {
        ActivityTaskHandler.Result response = handler.handle(service, domain, task);
        sendReply(task, response);
      } catch (CancellationException e) {
        RespondActivityTaskCanceledRequest cancelledRequest =
            new RespondActivityTaskCanceledRequest();
        cancelledRequest.setDetails(
            String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        sendReply(task, new Result(null, null, cancelledRequest, null));
      }
    }

    @Override
    public PollForActivityTaskResponse poll(
        IWorkflowService service, String domain, String taskList) throws TException {
      PollForActivityTaskRequest pollRequest = new PollForActivityTaskRequest();
      pollRequest.setDomain(domain);
      pollRequest.setIdentity(options.getIdentity());
      pollRequest.setTaskList(new TaskList().setName(taskList));
      if (log.isDebugEnabled()) {
        log.debug("poll request begin: " + pollRequest);
      }
      PollForActivityTaskResponse result = service.PollForActivityTask(pollRequest);
      if (result == null || result.getTaskToken() == null) {
        if (log.isDebugEnabled()) {
          log.debug("poll request returned no task");
        }
        return null;
      }
      if (log.isTraceEnabled()) {
        log.trace("poll request returned " + result);
      }
      return result;
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

    private void sendReply(PollForActivityTaskResponse task, ActivityTaskHandler.Result response)
        throws TException {
      RetryOptions ro = response.getRequestRetryOptions();
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        ro = options.getReportCompletionRetryOptions().merge(ro);
        taskCompleted.setTaskToken(task.getTaskToken());
        taskCompleted.setIdentity(options.getIdentity());
        Retryer.retry(ro, () -> service.RespondActivityTaskCompleted(taskCompleted));
      } else {
        RespondActivityTaskFailedRequest taskFailed = response.getTaskFailed();
        if (taskFailed != null) {
          ro = options.getReportFailureRetryOptions().merge(ro);
          taskFailed.setTaskToken(task.getTaskToken());
          taskFailed.setIdentity(options.getIdentity());
          Retryer.retry(ro, () -> service.RespondActivityTaskFailed(taskFailed));
        } else {
          RespondActivityTaskCanceledRequest taskCancelled = response.getTaskCancelled();
          if (taskCancelled != null) {
            taskCancelled.setTaskToken(task.getTaskToken());
            taskCancelled.setIdentity(options.getIdentity());
            ro = options.getReportFailureRetryOptions().merge(ro);
            Retryer.retry(ro, () -> service.RespondActivityTaskCanceled(taskCancelled));
          }
        }
      }
      // Manual activity completion
    }
  }
}
