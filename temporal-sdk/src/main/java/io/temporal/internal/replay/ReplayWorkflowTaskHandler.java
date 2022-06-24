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

package io.temporal.internal.replay;

import static io.temporal.internal.common.InternalUtils.createStickyTaskQueue;
import static io.temporal.internal.common.WorkflowExecutionUtils.isFullHistory;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.worker.LocalActivityTask;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutionFailingException;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.NonDeterministicException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
  private final WorkflowServiceStubs service;
  private final String stickyTaskQueueName;
  private final BiFunction<LocalActivityTask, Duration, Boolean> localActivityTaskPoller;

  public ReplayWorkflowTaskHandler(
      String namespace,
      ReplayWorkflowFactory asyncWorkflowFactory,
      WorkflowExecutorCache cache,
      SingleWorkerOptions options,
      String stickyTaskQueueName,
      Duration stickyTaskQueueScheduleToStartTimeout,
      WorkflowServiceStubs service,
      BiFunction<LocalActivityTask, Duration, Boolean> localActivityTaskPoller) {
    this.namespace = namespace;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.options = options;
    this.stickyTaskQueueName = stickyTaskQueueName;
    this.stickyTaskQueueScheduleToStartTimeout = stickyTaskQueueScheduleToStartTimeout;
    this.service = Objects.requireNonNull(service);
    this.localActivityTaskPoller = localActivityTaskPoller;
  }

  @Override
  public WorkflowTaskHandler.Result handleWorkflowTask(PollWorkflowTaskQueueResponse workflowTask)
      throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    Scope metricsScope =
        options.getMetricsScope().tagged(ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, workflowType));
    return handleWorkflowTaskWithQuery(workflowTask.toBuilder(), metricsScope);
  }

  private Result handleWorkflowTaskWithQuery(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Exception {
    boolean directQuery = workflowTask.hasQuery();
    AtomicBoolean createdNew = new AtomicBoolean();
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    WorkflowRunTaskHandler workflowRunTaskHandler = null;
    boolean useCache = stickyTaskQueueName != null;

    try {
      workflowRunTaskHandler =
          getOrCreateWorkflowExecutor(useCache, workflowTask, metricsScope, createdNew);

      boolean finalCommand;
      Result result;

      if (directQuery) {
        // Direct query happens when there is no reason (events) to produce a real persisted
        // workflow task.
        // But Server needs to notify the workflow about the query and get back the query result.
        // Server creates a fake non-persisted a PollWorkflowTaskResponse with just the query.
        // This WFT has no new events in the history to process
        // and the worker response on such a WFT can't contain any new commands either.
        QueryResult queryResult =
            workflowRunTaskHandler.handleQueryWorkflowTask(workflowTask, workflowTask.getQuery());
        finalCommand = queryResult.isWorkflowMethodCompleted();
        result = createDirectQueryResult(workflowTask, queryResult, null);
      } else {
        // main code path, handle workflow task that can have an embedded query
        WorkflowTaskResult wftResult = workflowRunTaskHandler.handleWorkflowTask(workflowTask);
        finalCommand = wftResult.isFinalCommand();
        result =
            createCompletedWFTRequest(
                workflowTask.getWorkflowType().getName(), workflowTask, wftResult);
      }

      if (useCache) {
        if (finalCommand) {
          // don't invalidate execution from the cache if we were not using cached value here
          cache.invalidate(execution, metricsScope, "FinalCommand", null);
        } else if (createdNew.get()) {
          cache.addToCache(execution, workflowRunTaskHandler);
        }
      }

      return result;
    } catch (Throwable e) {
      // Note here that the executor might not be in the cache, even when the caching is on. In that
      // case we need to close the executor explicitly. For items in the cache, invalidation
      // callback will try to close again, which should be ok.
      if (workflowRunTaskHandler != null) {
        workflowRunTaskHandler.close();
      }

      if (useCache) {
        cache.invalidate(execution, metricsScope, "Exception", e);
        // If history is full and exception occurred then sticky session hasn't been established
        // yet, and we can avoid doing a reset.
        if (!isFullHistory(workflowTask)) {
          resetStickyTaskQueue(execution);
        }
      }

      if (directQuery) {
        return createDirectQueryResult(workflowTask, null, e);
      } else {
        // this call rethrows an exception in some scenarios
        return failureToWFTResult(workflowTask, e);
      }
    } finally {
      if (useCache) {
        cache.markProcessingDone(execution);
      } else if (workflowRunTaskHandler != null) {
        // we close the execution in finally only if we don't use cache, otherwise it stays open
        workflowRunTaskHandler.close();
      }
    }
  }

  private Result createCompletedWFTRequest(
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

  private Result failureToWFTResult(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, Throwable e) throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    if (e instanceof WorkflowExecutionFailingException) {
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
                              .setFailure(((WorkflowExecutionFailingException) e).getFailure()))
                      .build())
              .build();
      return new WorkflowTaskHandler.Result(workflowType, response, null, null, null, false);
    }

    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    log.warn(
        "Workflow task processing failure. startedEventId={}, WorkflowId={}, RunId={}. If seen continuously the workflow might be stuck.",
        workflowTask.getStartedEventId(),
        execution.getWorkflowId(),
        execution.getRunId(),
        e);

    // Only fail workflow task on the first attempt, subsequent failures of the same workflow task
    // should timeout. This is to avoid spin on the failed workflow task as the service doesn't
    // yet increase the retry interval.
    if (workflowTask.getAttempt() > 1) {
      /*
       * TODO we shouldn't swallow Error even if workflowTask.getAttempt() == 1.
       *  But leaving as it is for now, because a trivial change to rethrow
       *  will leave us without reporting Errors as WorkflowTaskFailure to the server,
       *  which we probably should at least attempt to do for visibility that the Error occurs.
       */
      if (e instanceof Error) {
        throw (Error) e;
      }
      throw (Exception) e;
    }

    Failure failure = FailureConverter.exceptionToFailure(e);
    RespondWorkflowTaskFailedRequest.Builder failedRequest =
        RespondWorkflowTaskFailedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .setFailure(failure);
    if (e instanceof NonDeterministicException) {
      failedRequest.setCause(
          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR);
    }
    return new WorkflowTaskHandler.Result(
        workflowType, null, failedRequest.build(), null, null, false);
  }

  private Result createDirectQueryResult(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, QueryResult queryResult, Throwable e) {
    RespondQueryTaskCompletedRequest.Builder queryCompletedRequest =
        RespondQueryTaskCompletedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .setNamespace(namespace);

    if (e == null) {
      queryCompletedRequest.setCompletedType(QueryResultType.QUERY_RESULT_TYPE_ANSWERED);
      queryResult.getResponsePayloads().ifPresent(queryCompletedRequest::setQueryResult);
    } else {
      queryCompletedRequest.setCompletedType(QueryResultType.QUERY_RESULT_TYPE_FAILED);
      // TODO: Appropriate exception serialization.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);

      queryCompletedRequest.setErrorMessage(sw.toString());
    }

    return new Result(
        workflowTask.getWorkflowType().getName(),
        null,
        null,
        queryCompletedRequest.build(),
        null,
        false);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  private WorkflowRunTaskHandler getOrCreateWorkflowExecutor(
      boolean useCache,
      PollWorkflowTaskQueueResponse.Builder workflowTask,
      Scope metricsScope,
      AtomicBoolean createdNew)
      throws Exception {
    if (useCache) {
      return cache.getOrCreate(
          workflowTask,
          metricsScope,
          () -> {
            createdNew.set(true);
            return createStatefulHandler(workflowTask, metricsScope);
          });
    } else {
      createdNew.set(true);
      return createStatefulHandler(workflowTask, metricsScope);
    }
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

  private void resetStickyTaskQueue(WorkflowExecution execution) {
    service
        .futureStub()
        .resetStickyTaskQueue(
            ResetStickyTaskQueueRequest.newBuilder()
                .setNamespace(namespace)
                .setExecution(execution)
                .build());
  }
}
