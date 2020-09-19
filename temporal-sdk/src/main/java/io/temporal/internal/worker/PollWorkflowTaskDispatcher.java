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

import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.failure.FailureConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PollWorkflowTaskDispatcher
    implements ShutdownableTaskExecutor<PollWorkflowTaskQueueResponse> {

  private static final Logger log = LoggerFactory.getLogger(PollWorkflowTaskDispatcher.class);
  private final Map<String, Functions.Proc1<PollWorkflowTaskQueueResponse>> subscribers =
      new ConcurrentHashMap<>();
  private final Scope metricsScope;
  private final WorkflowServiceStubs service;
  private Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
      (t, e) -> log.error("uncaught exception", e);
  private final AtomicBoolean shutdown = new AtomicBoolean();

  public PollWorkflowTaskDispatcher(WorkflowServiceStubs service, Scope metricsScope) {
    this.service = Objects.requireNonNull(service);
    this.metricsScope = Objects.requireNonNull(metricsScope);
  }

  public PollWorkflowTaskDispatcher(
      WorkflowServiceStubs service,
      Scope metricsScope,
      Thread.UncaughtExceptionHandler exceptionHandler) {
    this.service = Objects.requireNonNull(service);
    this.metricsScope = Objects.requireNonNull(metricsScope);
    if (exceptionHandler != null) {
      this.uncaughtExceptionHandler = exceptionHandler;
    }
  }

  @Override
  public void process(PollWorkflowTaskQueueResponse t) {
    if (isShutdown()) {
      throw new RejectedExecutionException("shutdown");
    }
    String taskQueueName = t.getWorkflowExecutionTaskQueue().getName();
    if (subscribers.containsKey(taskQueueName)) {
      subscribers.get(taskQueueName).apply(t);
    } else {
      Exception exception =
          new Exception(
              String.format(
                  "No handler is subscribed for the PollWorkflowTaskQueueResponse.WorkflowExecutionTaskQueue %s",
                  taskQueueName));
      RespondWorkflowTaskFailedRequest request =
          RespondWorkflowTaskFailedRequest.newBuilder()
              .setTaskToken(t.getTaskToken())
              .setCause(WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_RESET_STICKY_TASK_QUEUE)
              .setFailure(FailureConverter.exceptionToFailure(exception))
              .build();
      log.warn("unexpected", exception);

      try {
        service
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
            .respondWorkflowTaskFailed(request);
      } catch (Exception e) {
        uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), e);
      }
    }
  }

  public void subscribe(String taskQueue, Functions.Proc1<PollWorkflowTaskQueueResponse> consumer) {
    subscribers.put(taskQueue, consumer);
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return shutdown.get();
  }

  @Override
  public void shutdown() {
    shutdown.set(true);
  }

  @Override
  public void shutdownNow() {
    shutdown.set(true);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {}
}
