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

import static io.temporal.internal.common.InternalUtils.createStickyTaskList;
import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.google.common.base.Throwables;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.worker.DecisionTaskHandler;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.decision.StickyExecutionAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.query.QueryResultType;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryResponse;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponseOrBuilder;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondQueryTaskCompletedRequest;
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

public final class ReplayDecisionTaskHandler implements DecisionTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayDecisionTaskHandler.class);

  private final ReplayWorkflowFactory workflowFactory;
  private final String namespace;
  private final DeciderCache cache;
  private final SingleWorkerOptions options;
  private final Duration stickyTaskListScheduleToStartTimeout;
  private final Functions.Func<Boolean> shutdownFn;
  private WorkflowServiceStubs service;
  private String stickyTaskListName;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller;

  public ReplayDecisionTaskHandler(
      String namespace,
      ReplayWorkflowFactory asyncWorkflowFactory,
      DeciderCache cache,
      SingleWorkerOptions options,
      String stickyTaskListName,
      Duration stickyTaskListScheduleToStartTimeout,
      WorkflowServiceStubs service,
      Functions.Func<Boolean> shutdownFn,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller) {
    this.namespace = namespace;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.options = options;
    this.stickyTaskListName = stickyTaskListName;
    this.stickyTaskListScheduleToStartTimeout = stickyTaskListScheduleToStartTimeout;
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
      String stackTrace = Throwables.getStackTraceAsString(e);
      RespondDecisionTaskFailedRequest failedRequest =
          RespondDecisionTaskFailedRequest.newBuilder()
              .setTaskToken(decisionTask.getTaskToken())
              .setDetails(options.getDataConverter().toData(stackTrace).get())
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
      if (stickyTaskListName == null) {
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
      } else if (stickyTaskListName != null && createdNew.get()) {
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

      if (stickyTaskListName != null) {
        cache.invalidate(decisionTask.getWorkflowExecution().getRunId());
      }
      throw e;
    } finally {
      if (stickyTaskListName == null && decider != null) {
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
      if (stickyTaskListName == null) {
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
      if (stickyTaskListName != null && createdNew.get()) {
        cache.addToCache(decisionTask, decider);
      }
      if (queryResult.isPresent()) {
        queryCompletedRequest.setQueryResult(queryResult.get());
      }
      queryCompletedRequest.setCompletedType(QueryResultType.Answered);
    } catch (Throwable e) {
      // TODO: Appropriate exception serialization.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      queryCompletedRequest.setErrorMessage(sw.toString());
      queryCompletedRequest.setCompletedType(QueryResultType.Failed);
    } finally {
      if (stickyTaskListName == null && decider != null) {
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

    if (stickyTaskListName != null && !stickyTaskListScheduleToStartTimeout.isZero()) {
      StickyExecutionAttributes.Builder attributes =
          StickyExecutionAttributes.newBuilder()
              .setWorkerTaskList(createStickyTaskList(stickyTaskListName))
              .setScheduleToStartTimeoutSeconds(
                  roundUpToSeconds(stickyTaskListScheduleToStartTimeout));
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
