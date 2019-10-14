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

package com.uber.cadence.internal.replay;

import static com.uber.cadence.internal.common.InternalUtils.createStickyTaskList;

import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryTaskCompletedType;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.StickyExecutionAttributes;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.worker.DecisionTaskHandler;
import com.uber.cadence.internal.worker.LocalActivityWorker;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayDecisionTaskHandler implements DecisionTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayDecisionTaskHandler.class);

  private final ReplayWorkflowFactory workflowFactory;
  private final String domain;
  private final DeciderCache cache;
  private final SingleWorkerOptions options;
  private final Duration stickyTaskListScheduleToStartTimeout;
  private IWorkflowService service;
  private String stickyTaskListName;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller;

  public ReplayDecisionTaskHandler(
      String domain,
      ReplayWorkflowFactory asyncWorkflowFactory,
      DeciderCache cache,
      SingleWorkerOptions options,
      String stickyTaskListName,
      Duration stickyTaskListScheduleToStartTimeout,
      IWorkflowService service,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller) {
    this.domain = domain;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.options = options;
    this.stickyTaskListName = stickyTaskListName;
    this.stickyTaskListScheduleToStartTimeout = stickyTaskListScheduleToStartTimeout;
    this.service = Objects.requireNonNull(service);
    this.laTaskPoller = laTaskPoller;
  }

  @Override
  public DecisionTaskHandler.Result handleDecisionTask(PollForDecisionTaskResponse decisionTask)
      throws Exception {
    try {
      return handleDecisionTaskImpl(decisionTask);
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
      if (log.isErrorEnabled()) {
        WorkflowExecution execution = decisionTask.getWorkflowExecution();
        log.error(
            "Workflow task failure. startedEventId="
                + decisionTask.getStartedEventId()
                + ", WorkflowID="
                + execution.getWorkflowId()
                + ", RunID="
                + execution.getRunId()
                + ". If see continuously the workflow might be stuck.",
            e);
      }
      RespondDecisionTaskFailedRequest failedRequest = new RespondDecisionTaskFailedRequest();
      failedRequest.setTaskToken(decisionTask.getTaskToken());
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      String stackTrace = sw.toString();
      failedRequest.setDetails(stackTrace.getBytes(StandardCharsets.UTF_8));
      return new DecisionTaskHandler.Result(null, failedRequest, null, null);
    }
  }

  private Result handleDecisionTaskImpl(PollForDecisionTaskResponse decisionTask) throws Throwable {

    if (decisionTask.isSetQuery()) {
      return processQuery(decisionTask);
    } else {
      return processDecision(decisionTask);
    }
  }

  private Result processDecision(PollForDecisionTaskResponse decisionTask) throws Throwable {
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

      if (stickyTaskListName != null && createdNew.get()) {
        cache.addToCache(decisionTask, decider);
      }

      if (log.isTraceEnabled()) {
        WorkflowExecution execution = decisionTask.getWorkflowExecution();
        log.trace(
            "WorkflowTask startedEventId="
                + decisionTask.getStartedEventId()
                + ", WorkflowID="
                + execution.getWorkflowId()
                + ", RunID="
                + execution.getRunId()
                + " completed with "
                + WorkflowExecutionUtils.prettyPrintDecisions(result.getDecisions())
                + " forceCreateNewDecisionTask "
                + result.getForceCreateNewDecisionTask());
      } else if (log.isDebugEnabled()) {
        WorkflowExecution execution = decisionTask.getWorkflowExecution();
        log.debug(
            "WorkflowTask startedEventId="
                + decisionTask.getStartedEventId()
                + ", WorkflowID="
                + execution.getWorkflowId()
                + ", RunID="
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

  private Result processQuery(PollForDecisionTaskResponse decisionTask) {
    RespondQueryTaskCompletedRequest queryCompletedRequest = new RespondQueryTaskCompletedRequest();
    queryCompletedRequest.setTaskToken(decisionTask.getTaskToken());
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

      byte[] queryResult = decider.query(decisionTask, decisionTask.getQuery());
      if (stickyTaskListName != null && createdNew.get()) {
        cache.addToCache(decisionTask, decider);
      }
      queryCompletedRequest.setQueryResult(queryResult);
      queryCompletedRequest.setCompletedType(QueryTaskCompletedType.COMPLETED);
    } catch (Throwable e) {
      // TODO: Appropriate exception serialization.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      queryCompletedRequest.setErrorMessage(sw.toString());
      queryCompletedRequest.setCompletedType(QueryTaskCompletedType.FAILED);
    } finally {
      if (stickyTaskListName == null && decider != null) {
        decider.close();
      } else {
        cache.markProcessingDone(decisionTask);
      }
    }
    return new Result(null, null, queryCompletedRequest, null);
  }

  private Result createCompletedRequest(
      PollForDecisionTaskResponse decisionTask, Decider.DecisionResult result) {
    RespondDecisionTaskCompletedRequest completedRequest =
        new RespondDecisionTaskCompletedRequest();
    completedRequest.setTaskToken(decisionTask.getTaskToken());
    completedRequest.setDecisions(result.getDecisions());
    completedRequest.setForceCreateNewDecisionTask(result.getForceCreateNewDecisionTask());

    if (stickyTaskListName != null) {
      StickyExecutionAttributes attributes = new StickyExecutionAttributes();
      attributes.setWorkerTaskList(createStickyTaskList(stickyTaskListName));
      attributes.setScheduleToStartTimeoutSeconds(
          (int) stickyTaskListScheduleToStartTimeout.getSeconds());
      completedRequest.setStickyAttributes(attributes);
    }
    return new Result(completedRequest, null, null, null);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  private Decider createDecider(PollForDecisionTaskResponse decisionTask) throws Exception {
    WorkflowType workflowType = decisionTask.getWorkflowType();
    List<HistoryEvent> events = decisionTask.getHistory().getEvents();
    // Sticky decision task with partial history
    if (events.isEmpty() || events.get(0).getEventId() > 1) {
      GetWorkflowExecutionHistoryRequest getHistoryRequest =
          new GetWorkflowExecutionHistoryRequest()
              .setDomain(domain)
              .setExecution(decisionTask.getWorkflowExecution());
      GetWorkflowExecutionHistoryResponse getHistoryResponse =
          service.GetWorkflowExecutionHistory(getHistoryRequest);
      decisionTask.setHistory(getHistoryResponse.getHistory());
      decisionTask.setNextPageToken(getHistoryResponse.getNextPageToken());
    }
    DecisionsHelper decisionsHelper = new DecisionsHelper(decisionTask);
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType);
    return new ReplayDecider(service, domain, workflow, decisionsHelper, options, laTaskPoller);
  }
}
