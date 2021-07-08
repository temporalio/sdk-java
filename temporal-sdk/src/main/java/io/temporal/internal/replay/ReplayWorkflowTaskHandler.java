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
import static io.temporal.internal.common.WorkflowExecutionUtils.isFullHistory;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.serviceclient.MetricsTag;
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
  private final WorkflowServiceStubs service;
  private final String stickyTaskQueueName;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> localActivityTaskPoller;

  public ReplayWorkflowTaskHandler(
      String namespace,
      ReplayWorkflowFactory asyncWorkflowFactory,
      WorkflowExecutorCache cache,
      SingleWorkerOptions options,
      String stickyTaskQueueName,
      Duration stickyTaskQueueScheduleToStartTimeout,
      WorkflowServiceStubs service,
      Functions.Func<Boolean> shutdownFn,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> localActivityTaskPoller) {
    this.namespace = namespace;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.options = options;
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.stickyTaskQueueScheduleToStartTimeout = stickyTaskQueueScheduleToStartTimeout;
    this.shutdownFn = shutdownFn;
    this.service = Objects.requireNonNull(service);
    this.localActivityTaskPoller = localActivityTaskPoller;
  }

  @Override
  public WorkflowTaskHandler.Result handleWorkflowTask(PollWorkflowTaskQueueResponse workflowTask)
      throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    Scope metricsScope =
        options.getMetricsScope().tagged(ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, workflowType));
    try {
      if (workflowTask.hasQuery()) {
        // Legacy query codepath
        return handleQueryOnlyWorkflowTask(workflowTask.toBuilder(), metricsScope);
      } else {
        return handleWorkflowTaskWithEmbeddedQuery(workflowTask.toBuilder(), metricsScope);
      }
    } catch (Throwable e) {
      metricsScope.counter(MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER).inc(1);
      return failureToResult(workflowTask, e);
    }
  }

  private Result failureToResult(PollWorkflowTaskQueueResponse workflowTask, Throwable e)
      throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    if (e instanceof WorkflowExecutionException) {
      RespondWorkflowTaskCompletedRequest response =
          RespondWorkflowTaskCompletedRequest.newBuilder()
              .setTaskToken(workflowTask.getTaskToken())
              .setIdentity(options.getIdentity())
              .setNamespace(namespace)
              .setBinaryChecksum(options.getBinaryChecksum())
              .addCommands(
                  Command.newBuilder()
                      .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
                      .setFailWorkflowExecutionCommandAttributes(
                          FailWorkflowExecutionCommandAttributes.newBuilder()
                              .setFailure(((WorkflowExecutionException) e).getFailure()))
                      .build())
              .build();
      return new WorkflowTaskHandler.Result(workflowType, response, null, null, null, false);
    }
    // Only fail workflow task on the first attempt, subsequent failures of the same workflow task
    // should timeout. This is to avoid spin on the failed workflow task as the service doesn't
    // yet increase the retry interval.
    if (workflowTask.getAttempt() > 1) {
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

  private WorkflowRunTaskHandler getOrCreateWorkflowExecutor(
      PollWorkflowTaskQueueResponse.Builder workflowTask,
      Scope metricsScope,
      AtomicBoolean createdNew)
      throws Exception {
    WorkflowRunTaskHandler workflowRunTaskHandler;
    if (stickyTaskQueueName == null) {
      workflowRunTaskHandler = createStatefulHandler(workflowTask, metricsScope);
    } else {
      workflowRunTaskHandler =
          cache.getOrCreate(
              workflowTask,
              metricsScope,
              () -> {
                createdNew.set(true);
                return createStatefulHandler(workflowTask, metricsScope);
              });
    }
    return workflowRunTaskHandler;
  }

  private Result handleWorkflowTaskWithEmbeddedQuery(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Throwable {
    AtomicBoolean createdNew = new AtomicBoolean();
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    String runId = execution.getRunId();
    WorkflowRunTaskHandler workflowRunTaskHandler = null;
    try {
      workflowRunTaskHandler = getOrCreateWorkflowExecutor(workflowTask, metricsScope, createdNew);
      WorkflowTaskResult result = workflowRunTaskHandler.handleWorkflowTask(workflowTask);
      if (result.isFinalCommand()) {
        cache.invalidate(execution, metricsScope);
      } else if (stickyTaskQueueName != null && createdNew.get()) {
        cache.addToCache(runId, workflowRunTaskHandler);
      }
      return createCompletedRequest(workflowTask.getWorkflowType().getName(), workflowTask, result);
    } catch (Throwable e) {
      // Note here that the executor might not be in the cache, even when the caching is on. In that
      // case we need to close the executor explicitly. For items in the cache, invalidation
      // callback will try to close again, which should be ok.
      if (workflowRunTaskHandler != null) {
        workflowRunTaskHandler.close();
      }

      if (stickyTaskQueueName != null) {
        cache.invalidate(execution, metricsScope);
        // If history if full and exception occurred then sticky session hasn't been established
        // yet and we can avoid doing a reset.
        if (!isFullHistory(workflowTask)) {
          resetStickyTaskQueue(execution);
        }
      }
      throw e;
    } finally {
      if (stickyTaskQueueName == null && workflowRunTaskHandler != null) {
        workflowRunTaskHandler.close();
      } else {
        cache.markProcessingDone(runId);
      }
    }
  }

  private void resetStickyTaskQueue(WorkflowExecution execution) {
    service
        .futureStub()
        .resetStickyTaskQueue(
            ResetStickyTaskQueueRequest.newBuilder()
                .setNamespace(namespace)
                .setExecution(execution)
                .build());
  }

  private Result handleQueryOnlyWorkflowTask(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) {
    RespondQueryTaskCompletedRequest.Builder queryCompletedRequest =
        RespondQueryTaskCompletedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .setNamespace(namespace);
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    String runId = execution.getRunId();
    WorkflowRunTaskHandler workflowRunTaskHandler = null;
    AtomicBoolean createdNew = new AtomicBoolean();
    try {
      workflowRunTaskHandler = getOrCreateWorkflowExecutor(workflowTask, metricsScope, createdNew);
      Optional<Payloads> queryResult =
          workflowRunTaskHandler.handleQueryWorkflowTask(workflowTask, workflowTask.getQuery());
      if (stickyTaskQueueName != null && createdNew.get()) {
        cache.addToCache(runId, workflowRunTaskHandler);
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
      if (stickyTaskQueueName == null && workflowRunTaskHandler != null) {
        workflowRunTaskHandler.close();
      } else {
        cache.markProcessingDone(runId);
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
      WorkflowTaskResult result) {
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    if (log.isTraceEnabled()) {
      log.trace(
          "WorkflowTask startedEventId="
              + workflowTask.getStartedEventId()
              + ", WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + " completed with \n"
              + WorkflowExecutionUtils.prettyPrintCommands(result.getCommands()));
    } else if (log.isDebugEnabled()) {
      log.debug(
          "WorkflowTask startedEventId="
              + workflowTask.getStartedEventId()
              + ", WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + " completed with "
              + result.getCommands().size()
              + " new commands");
    }
    RespondWorkflowTaskCompletedRequest.Builder completedRequest =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .addAllCommands(result.getCommands())
            .putAllQueryResults(result.getQueryResults())
            .setForceCreateNewWorkflowTask(result.isForceWorkflowTask())
            .setReturnNewWorkflowTask(result.isForceWorkflowTask());

    if (stickyTaskQueueName != null
        && (stickyTaskQueueScheduleToStartTimeout == null
            || !stickyTaskQueueScheduleToStartTimeout.isZero())) {
      StickyExecutionAttributes.Builder attributes =
          StickyExecutionAttributes.newBuilder()
              .setWorkerTaskQueue(createStickyTaskQueue(stickyTaskQueueName));
      if (stickyTaskQueueScheduleToStartTimeout != null) {
        attributes.setScheduleToStartTimeout(
            ProtobufTimeUtils.toProtoDuration(stickyTaskQueueScheduleToStartTimeout));
      }
      completedRequest.setStickyAttributes(attributes);
    }
    return new Result(
        workflowType, completedRequest.build(), null, null, null, result.isFinalCommand());
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  // TODO(maxim): Consider refactoring that avoids mutating workflow task.
  private WorkflowRunTaskHandler createStatefulHandler(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Exception {
    WorkflowType workflowType = workflowTask.getWorkflowType();
    List<HistoryEvent> events = workflowTask.getHistory().getEventsList();
    // Sticky workflow task with partial history.
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
      workflowTask
          .setHistory(getHistoryResponse.getHistory())
          .setNextPageToken(getHistoryResponse.getNextPageToken());
    }
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType);
    return new ReplayWorkflowRunTaskHandler(
        service, namespace, workflow, workflowTask, options, metricsScope, localActivityTaskPoller);
  }
}
