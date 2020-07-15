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

package io.temporal.internal.replay;

import static io.temporal.internal.common.InternalUtils.createStickyTaskQueue;
import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;
import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayWorkflowTaskHandler implements WorkflowTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayWorkflowTaskHandler.class);

  private final ReplayWorkflowFactory workflowFactory;
  private final String namespace;
  private final WorkflowExecutorCache cache;
  private final SingleWorkerOptions options;
  private final Duration stickyTaskQueueScheduleToStartTimeout;
  private final Functions.Func<Boolean> shutdownFn;
  private WorkflowServiceStubs service;
  private String stickyTaskQueueName;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller;

  public ReplayWorkflowTaskHandler(
      String namespace,
      ReplayWorkflowFactory asyncWorkflowFactory,
      WorkflowExecutorCache cache,
      SingleWorkerOptions options,
      String stickyTaskQueueName,
      Duration stickyTaskQueueScheduleToStartTimeout,
      WorkflowServiceStubs service,
      Functions.Func<Boolean> shutdownFn,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller) {
    this.namespace = namespace;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.options = options;
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.stickyTaskQueueScheduleToStartTimeout = stickyTaskQueueScheduleToStartTimeout;
    this.shutdownFn = shutdownFn;
    this.service = Objects.requireNonNull(service);
    this.laTaskPoller = laTaskPoller;
  }

  @Override
  public WorkflowTaskHandler.Result handleWorkflowTask(PollWorkflowTaskQueueResponse workflowTask)
      throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    Scope metricsScope =
        options.getMetricsScope().tagged(ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, workflowType));
    try {
      return handleWorkflowTaskImpl(workflowTask.toBuilder(), metricsScope);
    } catch (Throwable e) {
      metricsScope.counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER).inc(1);
      // Only fail workflow task on the first attempt, subsequent failures of the same workflow task
      // should timeout. This is to avoid spin on the failed workflow task as the service doesn't
      // yet increase the retry interval.
      if (workflowTask.getAttempt() > 0) {
        if (e instanceof Error) {
          throw (Error) e;
        }
        throw (Exception) e;
      }
      if (log.isErrorEnabled() && !shutdownFn.apply()) {
        WorkflowExecution execution = workflowTask.getWorkflowExecution();
        log.error(
            "Workflow task failure. startedEventId="
                + workflowTask.getStartedEventId()
                + ", WorkflowId="
                + execution.getWorkflowId()
                + ", RunId="
                + execution.getRunId()
                + ". If see continuously the workflow might be stuck.",
            e);
      }
      Failure failure = FailureConverter.exceptionToFailure(e);
      RespondWorkflowTaskFailedRequest failedRequest =
          RespondWorkflowTaskFailedRequest.newBuilder()
              .setTaskToken(workflowTask.getTaskToken())
              .setFailure(failure)
              .build();
      return new WorkflowTaskHandler.Result(workflowType, null, failedRequest, null, null, false);
    }
  }

  private Result handleWorkflowTaskImpl(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Throwable {
    if (workflowTask.hasQuery()) {
      // Legacy query codepath
      return handleQueryOnlyWorkflowTask(workflowTask, metricsScope);
    } else {
      // Note that if workflowTask.getQueriesCount() > 0 this branch is taken as well
      return handleWorkflowTaskWithEmbeddedQuery(workflowTask, metricsScope);
    }
  }

  private Result handleWorkflowTaskWithEmbeddedQuery(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Throwable {
    WorkflowExecutor workflowExecutor = null;
    AtomicBoolean createdNew = new AtomicBoolean();
    try {
      if (stickyTaskQueueName == null) {
        workflowExecutor = createWorkflowExecutor(workflowTask, metricsScope);
      } else {
        workflowExecutor =
            cache.getOrCreate(
                workflowTask,
                metricsScope,
                () -> {
                  createdNew.set(true);
                  return createWorkflowExecutor(workflowTask, metricsScope);
                });
      }

      WorkflowExecutor.WorkflowTaskResult result =
          workflowExecutor.handleWorkflowTask(workflowTask);

      if (result.isFinalCommand()) {
        cache.invalidate(workflowTask.getWorkflowExecution().getRunId(), metricsScope);
      } else if (stickyTaskQueueName != null && createdNew.get()) {
        cache.addToCache(workflowTask, workflowExecutor);
      }

      if (log.isTraceEnabled()) {
        WorkflowExecution execution = workflowTask.getWorkflowExecution();
        log.trace(
            "WorkflowTask startedEventId="
                + workflowTask.getStartedEventId()
                + ", WorkflowId="
                + execution.getWorkflowId()
                + ", RunId="
                + execution.getRunId()
                + " completed with \n"
                + WorkflowExecutionUtils.prettyPrintCommands(result.getCommands())
                + "\nforceCreateNewWorkflowTask "
                + result.getForceCreateNewWorkflowTask());
      } else if (log.isDebugEnabled()) {
        WorkflowExecution execution = workflowTask.getWorkflowExecution();
        log.debug(
            "WorkflowTask startedEventId="
                + workflowTask.getStartedEventId()
                + ", WorkflowId="
                + execution.getWorkflowId()
                + ", RunId="
                + execution.getRunId()
                + " completed with "
                + result.getCommands().size()
                + " new commands"
                + " forceCreateNewWorkflowTask "
                + result.getForceCreateNewWorkflowTask());
      }
      return createCompletedRequest(workflowTask.getWorkflowType().getName(), workflowTask, result);
    } catch (Throwable e) {
      // Note here that the executor might not be in the cache, even when the caching is on. In that
      // case we need to close the executor explicitly. For items in the cache, invalidation
      // callback will try to close again, which should be ok.
      if (workflowExecutor != null) {
        workflowExecutor.close();
      }

      if (stickyTaskQueueName != null) {
        cache.invalidate(workflowTask.getWorkflowExecution().getRunId(), metricsScope);
      }
      throw e;
    } finally {
      if (stickyTaskQueueName == null && workflowExecutor != null) {
        workflowExecutor.close();
      } else {
        cache.markProcessingDone(workflowTask);
      }
    }
  }

  private Result handleQueryOnlyWorkflowTask(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) {
    RespondQueryTaskCompletedRequest.Builder queryCompletedRequest =
        RespondQueryTaskCompletedRequest.newBuilder().setTaskToken(workflowTask.getTaskToken());
    WorkflowExecutor workflowExecutor = null;
    AtomicBoolean createdNew = new AtomicBoolean();
    try {
      if (stickyTaskQueueName == null) {
        workflowExecutor = createWorkflowExecutor(workflowTask, metricsScope);
      } else {
        workflowExecutor =
            cache.getOrCreate(
                workflowTask,
                metricsScope,
                () -> {
                  createdNew.set(true);
                  return createWorkflowExecutor(workflowTask, metricsScope);
                });
      }

      Optional<Payloads> queryResult =
          workflowExecutor.handleQueryWorkflowTask(workflowTask, workflowTask.getQuery());
      if (stickyTaskQueueName != null && createdNew.get()) {
        cache.addToCache(workflowTask, workflowExecutor);
      }
      if (queryResult.isPresent()) {
        queryCompletedRequest.setQueryResult(queryResult.get());
      }
      queryCompletedRequest.setCompletedType(QueryResultType.QUERY_RESULT_TYPE_ANSWERED);
    } catch (Throwable e) {
      // TODO: Appropriate exception serialization.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      queryCompletedRequest.setErrorMessage(sw.toString());
      queryCompletedRequest.setCompletedType(QueryResultType.QUERY_RESULT_TYPE_FAILED);
    } finally {
      if (stickyTaskQueueName == null && workflowExecutor != null) {
        workflowExecutor.close();
      } else {
        cache.markProcessingDone(workflowTask);
      }
    }
    return new Result(
        workflowTask.getWorkflowType().getName(),
        null,
        null,
        queryCompletedRequest.build(),
        null,
        false);
  }

  private Result createCompletedRequest(
      String workflowType,
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      WorkflowExecutor.WorkflowTaskResult result) {
    RespondWorkflowTaskCompletedRequest.Builder completedRequest =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .addAllCommands(result.getCommands())
            .putAllQueryResults(result.getQueryResults())
            .setForceCreateNewWorkflowTask(result.getForceCreateNewWorkflowTask());

    if (stickyTaskQueueName != null && !stickyTaskQueueScheduleToStartTimeout.isZero()) {
      StickyExecutionAttributes.Builder attributes =
          StickyExecutionAttributes.newBuilder()
              .setWorkerTaskQueue(createStickyTaskQueue(stickyTaskQueueName))
              .setScheduleToStartTimeoutSeconds(
                  roundUpToSeconds(stickyTaskQueueScheduleToStartTimeout));
      completedRequest.setStickyAttributes(attributes);
    }
    return new Result(
        workflowType, completedRequest.build(), null, null, null, result.isFinalCommand());
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  private WorkflowExecutor createWorkflowExecutor(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Exception {
    WorkflowType workflowType = workflowTask.getWorkflowType();
    List<HistoryEvent> events = workflowTask.getHistory().getEventsList();
    // Sticky workflow task with partial history
    if (events.isEmpty() || events.get(0).getEventId() > 1) {
      GetWorkflowExecutionHistoryRequest getHistoryRequest =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(namespace)
              .setExecution(workflowTask.getWorkflowExecution())
              .build();
      GetWorkflowExecutionHistoryResponse getHistoryResponse =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .getWorkflowExecutionHistory(getHistoryRequest);
      workflowTask.setHistory(getHistoryResponse.getHistory());
      workflowTask.setNextPageToken(getHistoryResponse.getNextPageToken());
    }
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType);
    return new ReplayWorkflowExecutor(
        service, namespace, workflow, workflowTask, options, metricsScope, laTaskPoller);
  }
}
