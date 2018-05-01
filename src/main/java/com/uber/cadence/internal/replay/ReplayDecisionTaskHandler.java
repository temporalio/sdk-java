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

import com.uber.cadence.Decision;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryTaskCompletedType;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.worker.DecisionTaskHandler;
import com.uber.cadence.internal.worker.DecisionTaskWithHistoryIterator;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.m3.tally.Scope;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayDecisionTaskHandler implements DecisionTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayDecisionTaskHandler.class);

  private final ReplayWorkflowFactory workflowFactory;
  private final String domain;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;

  public ReplayDecisionTaskHandler(
      String domain, ReplayWorkflowFactory asyncWorkflowFactory, SingleWorkerOptions options) {
    this.domain = domain;
    this.workflowFactory = asyncWorkflowFactory;
    this.metricsScope = options.getMetricsScope();
    this.enableLoggingInReplay = options.getEnableLoggingInReplay();
  }

  @Override
  public DecisionTaskHandler.Result handleDecisionTask(
      DecisionTaskWithHistoryIterator decisionTaskIterator) throws Exception {
    try {
      return handleDecisionTaskImpl(decisionTaskIterator);
    } catch (Throwable e) {
      metricsScope.counter(MetricsType.DECISION_EXECUTION_FAILED_COUNTER).inc(1);
      PollForDecisionTaskResponse decisionTask = decisionTaskIterator.getDecisionTask();
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

  private Result handleDecisionTaskImpl(DecisionTaskWithHistoryIterator decisionTaskIterator)
      throws Throwable {
    HistoryHelper historyHelper = new HistoryHelper(decisionTaskIterator);
    ReplayDecider decider = createDecider(historyHelper);
    PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
    if (decisionTask.isSetQuery()) {
      RespondQueryTaskCompletedRequest queryCompletedRequest =
          new RespondQueryTaskCompletedRequest();
      queryCompletedRequest.setTaskToken(decisionTask.getTaskToken());
      try {
        byte[] queryResult = decider.query(decisionTask.getQuery());
        queryCompletedRequest.setQueryResult(queryResult);
        queryCompletedRequest.setCompletedType(QueryTaskCompletedType.COMPLETED);
      } catch (Throwable e) {
        // TODO: Appropriate exception serialization.
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        queryCompletedRequest.setErrorMessage(sw.toString());
        queryCompletedRequest.setCompletedType(QueryTaskCompletedType.FAILED);
      }
      return new DecisionTaskHandler.Result(null, null, queryCompletedRequest, null);
    } else {
      decider.decide();
      DecisionsHelper decisionsHelper = decider.getDecisionsHelper();
      List<Decision> decisions = decisionsHelper.getDecisions();
      byte[] context = decisionsHelper.getWorkflowContextDataToReturn();
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
                + WorkflowExecutionUtils.prettyPrintDecisions(decisions));
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
                + decisions.size()
                + " new decisions");
      }
      RespondDecisionTaskCompletedRequest completedRequest =
          new RespondDecisionTaskCompletedRequest();
      completedRequest.setTaskToken(decisionTask.getTaskToken());
      completedRequest.setDecisions(decisions);
      completedRequest.setExecutionContext(context);
      return new DecisionTaskHandler.Result(completedRequest, null, null, null);
    }
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  private ReplayDecider createDecider(HistoryHelper historyHelper) throws Exception {
    PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
    WorkflowType workflowType = decisionTask.getWorkflowType();
    DecisionsHelper decisionsHelper = new DecisionsHelper(decisionTask);
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType);
    return new ReplayDecider(
        domain, workflow, historyHelper, decisionsHelper, metricsScope, enableLoggingInReplay);
  }
}
