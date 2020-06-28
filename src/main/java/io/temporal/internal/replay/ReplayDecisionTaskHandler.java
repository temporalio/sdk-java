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

import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.common.v1.WorkflowType;
import io.temporal.enums.v1.QueryResultType;
import io.temporal.failure.FailureConverter;
import io.temporal.failure.v1.Failure;
import io.temporal.history.v1.HistoryEvent;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.DecisionTaskHandler;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.workflow.Functions;
import io.temporal.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponseOrBuilder;
import io.temporal.workflowservice.v1.RespondDecisionTaskCompletedRequest;
import io.temporal.workflowservice.v1.RespondDecisionTaskFailedRequest;
import io.temporal.workflowservice.v1.RespondQueryTaskCompletedRequest;
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

public final class ReplayDecisionTaskHandler implements DecisionTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayDecisionTaskHandler.class);

  private final ReplayWorkflowFactory workflowFactory;
  private final String namespace;
  private final DeciderCache cache;
  private final SingleWorkerOptions options;
  private final Duration stickyTaskQueueScheduleToStartTimeout;
  private final Functions.Func<Boolean> shutdownFn;
  private WorkflowServiceStubs service;
  private String stickyTaskQueueName;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller;

  public ReplayDecisionTaskHandler(
      String namespace,
      ReplayWorkflowFactory asyncWorkflowFactory,
      DeciderCache cache,
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
  public DecisionTaskHandler.Result handleDecisionTask(PollForDecisionTaskResponse decisionTask)
      throws Exception {
    try {
      return handleDecisionTaskImpl(decisionTask.toBuilder());
    } catch (Throwable e) {
      options.getMetricsScope().counter(MetricsType.DECISION_EXECUTION_FAILED_COUNTER).inc(1);
      // Only fail decision on first attempt, subsequent failure on the same decision task will
      // timeout. This is to avoid spin on the failed decision task.
      if (decisionTask.getAttempt() > 0) {
        if (e instanceof Error) {
          throw (Error) e;
        }
        throw (Exception) e;
      }
      if (log.isErrorEnabled() && !shutdownFn.apply()) {
        WorkflowExecution execution = decisionTask.getWorkflowExecution();
        log.error(
            "Workflow task failure. startedEventId="
                + decisionTask.getStartedEventId()
                + ", WorkflowId="
                + execution.getWorkflowId()
                + ", RunId="
                + execution.getRunId()
                + ". If see continuously the workflow might be stuck.",
            e);
      }
      Failure failure = FailureConverter.exceptionToFailure(e);
      RespondDecisionTaskFailedRequest failedRequest =
          RespondDecisionTaskFailedRequest.newBuilder()
              .setTaskToken(decisionTask.getTaskToken())
              .setFailure(failure)
              .build();
      return new DecisionTaskHandler.Result(null, failedRequest, null, null, false);
    }
  }

  private Result handleDecisionTaskImpl(PollForDecisionTaskResponse.Builder decisionTask)
      throws Throwable {
    if (decisionTask.hasQuery()) {
      // Legacy query codepath
      return processQuery(decisionTask);
    } else {
      // Note that if decisionTask.getQueriesCount() > 0 this branch is taken as well
      return processDecision(decisionTask);
    }
  }

  private Result processDecision(PollForDecisionTaskResponse.Builder decisionTask)
      throws Throwable {
    Decider decider = null;
    AtomicBoolean createdNew = new AtomicBoolean();
    try {
      if (stickyTaskQueueName == null) {
        decider = createDecider(decisionTask);
      } else {
        decider =
            cache.getOrCreate(
                decisionTask,
                () -> {
                  createdNew.set(true);
                  return createDecider(decisionTask);
                });
      }

      Decider.DecisionResult result = decider.decide(decisionTask);

      if (result.isFinalDecision()) {
        cache.invalidate(decisionTask.getWorkflowExecution().getRunId());
      } else if (stickyTaskQueueName != null && createdNew.get()) {
        cache.addToCache(decisionTask, decider);
      }

      if (log.isTraceEnabled()) {
        WorkflowExecution execution = decisionTask.getWorkflowExecution();
        log.trace(
            "WorkflowTask startedEventId="
                + decisionTask.getStartedEventId()
                + ", WorkflowId="
                + execution.getWorkflowId()
                + ", RunId="
                + execution.getRunId()
                + " completed with \n"
                + WorkflowExecutionUtils.prettyPrintDecisions(result.getDecisions())
                + "\nforceCreateNewDecisionTask "
                + result.getForceCreateNewDecisionTask());
      } else if (log.isDebugEnabled()) {
        WorkflowExecution execution = decisionTask.getWorkflowExecution();
        log.debug(
            "WorkflowTask startedEventId="
                + decisionTask.getStartedEventId()
                + ", WorkflowId="
                + execution.getWorkflowId()
                + ", RunId="
                + execution.getRunId()
                + " completed with "
                + result.getDecisions().size()
                + " new decisions"
                + " forceCreateNewDecisionTask "
                + result.getForceCreateNewDecisionTask());
      }
      return createCompletedRequest(decisionTask, result);
    } catch (Throwable e) {
      // Note here that the decider might not be in the cache, even sticky is on. In that case we
      // need to close the decider explicitly.
      // For items in the cache, invalidation callback will try to close again, which should be ok.
      if (decider != null) {
        decider.close();
      }

      if (stickyTaskQueueName != null) {
        cache.invalidate(decisionTask.getWorkflowExecution().getRunId());
      }
      throw e;
    } finally {
      if (stickyTaskQueueName == null && decider != null) {
        decider.close();
      } else {
        cache.markProcessingDone(decisionTask);
      }
    }
  }

  private Result processQuery(PollForDecisionTaskResponse.Builder decisionTask) {
    RespondQueryTaskCompletedRequest.Builder queryCompletedRequest =
        RespondQueryTaskCompletedRequest.newBuilder().setTaskToken(decisionTask.getTaskToken());
    Decider decider = null;
    AtomicBoolean createdNew = new AtomicBoolean();
    try {
      if (stickyTaskQueueName == null) {
        decider = createDecider(decisionTask);
      } else {
        decider =
            cache.getOrCreate(
                decisionTask,
                () -> {
                  createdNew.set(true);
                  return createDecider(decisionTask);
                });
      }

      Optional<Payloads> queryResult = decider.query(decisionTask, decisionTask.getQuery());
      if (stickyTaskQueueName != null && createdNew.get()) {
        cache.addToCache(decisionTask, decider);
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
      if (stickyTaskQueueName == null && decider != null) {
        decider.close();
      } else {
        cache.markProcessingDone(decisionTask);
      }
    }
    return new Result(null, null, queryCompletedRequest.build(), null, false);
  }

  private Result createCompletedRequest(
      PollForDecisionTaskResponseOrBuilder decisionTask, Decider.DecisionResult result) {
    RespondDecisionTaskCompletedRequest.Builder completedRequest =
        RespondDecisionTaskCompletedRequest.newBuilder()
            .setTaskToken(decisionTask.getTaskToken())
            .addAllDecisions(result.getDecisions())
            .putAllQueryResults(result.getQueryResults())
            .setForceCreateNewDecisionTask(result.getForceCreateNewDecisionTask());

    if (stickyTaskQueueName != null && !stickyTaskQueueScheduleToStartTimeout.isZero()) {
      StickyExecutionAttributes.Builder attributes =
          StickyExecutionAttributes.newBuilder()
              .setWorkerTaskQueue(createStickyTaskQueue(stickyTaskQueueName))
              .setScheduleToStartTimeoutSeconds(
                  roundUpToSeconds(stickyTaskQueueScheduleToStartTimeout));
      completedRequest.setStickyAttributes(attributes);
    }
    return new Result(completedRequest.build(), null, null, null, result.isFinalDecision());
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  private Decider createDecider(PollForDecisionTaskResponse.Builder decisionTask) throws Exception {
    WorkflowType workflowType = decisionTask.getWorkflowType();
    List<HistoryEvent> events = decisionTask.getHistory().getEventsList();
    // Sticky decision task with partial history
    if (events.isEmpty() || events.get(0).getEventId() > 1) {
      GetWorkflowExecutionHistoryRequest getHistoryRequest =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(namespace)
              .setExecution(decisionTask.getWorkflowExecution())
              .build();
      GetWorkflowExecutionHistoryResponse getHistoryResponse =
          service.blockingStub().getWorkflowExecutionHistory(getHistoryRequest);
      decisionTask.setHistory(getHistoryResponse.getHistory());
      decisionTask.setNextPageToken(getHistoryResponse.getNextPageToken());
    }
    DecisionsHelper decisionsHelper = new DecisionsHelper(decisionTask);
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType);
    return new ReplayDecider(service, namespace, workflow, decisionsHelper, options, laTaskPoller);
  }
}
