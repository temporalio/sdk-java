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

import com.uber.cadence.*;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.worker.DecisionTaskHandler;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.m3.tally.Scope;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayDecisionTaskHandler implements DecisionTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayDecisionTaskHandler.class);

  private final ReplayWorkflowFactory workflowFactory;
  private final String domain;
  private final DeciderCache cache;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private final Duration stickyTaskListScheduleToStartTimeout;
  private IWorkflowService service;
  private String stickyTaskListName;

  public ReplayDecisionTaskHandler(
      String domain,
      ReplayWorkflowFactory asyncWorkflowFactory,
      DeciderCache cache,
      SingleWorkerOptions options,
      String stickyTaskListName,
      Duration stickyTaskListScheduleToStartTimeout,
      IWorkflowService service) {
    this.domain = domain;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.metricsScope = options.getMetricsScope();
    this.enableLoggingInReplay = options.getEnableLoggingInReplay();
    this.stickyTaskListName = stickyTaskListName;
    this.stickyTaskListScheduleToStartTimeout = stickyTaskListScheduleToStartTimeout;
    this.service = Objects.requireNonNull(service);
  }

  @Override
  public DecisionTaskHandler.Result handleDecisionTask(PollForDecisionTaskResponse decisionTask)
      throws Exception {
    try {
      return handleDecisionTaskImpl(decisionTask);
    } catch (Throwable e) {
      metricsScope.counter(MetricsType.DECISION_EXECUTION_FAILED_COUNTER).inc(1);
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
    try {
      decider =
          stickyTaskListName == null
              ? createDecider(decisionTask)
              : cache.getOrCreate(decisionTask, this::createDecider);
      List<Decision> decisions = decider.decide(decisionTask);
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
      return createCompletedRequest(decisionTask, decisions);
    } catch (Throwable e) {
      if (stickyTaskListName != null) {
        cache.invalidate(decisionTask);
      }
      throw e;
    } finally {
      if (stickyTaskListName == null && decider != null) {
        decider.close();
      }
    }
  }

  private Result processQuery(PollForDecisionTaskResponse decisionTask) {
    RespondQueryTaskCompletedRequest queryCompletedRequest = new RespondQueryTaskCompletedRequest();
    queryCompletedRequest.setTaskToken(decisionTask.getTaskToken());
    Decider decider = null;
    try {
      decider =
          stickyTaskListName == null
              ? createDecider(decisionTask)
              : cache.getOrCreate(decisionTask, this::createDecider);
      byte[] queryResult = decider.query(decisionTask, decisionTask.getQuery());
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
      }
    }
    return new Result(null, null, queryCompletedRequest, null);
  }

  private Result createCompletedRequest(
      PollForDecisionTaskResponse decisionTask, List<Decision> decisions) {
    RespondDecisionTaskCompletedRequest completedRequest =
        new RespondDecisionTaskCompletedRequest();
    completedRequest.setTaskToken(decisionTask.getTaskToken());
    completedRequest.setDecisions(decisions);

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
    DecisionsHelper decisionsHelper = new DecisionsHelper(decisionTask);
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType);
    return new ReplayDecider(
        service, domain, workflow, decisionsHelper, metricsScope, enableLoggingInReplay);
  }
}
