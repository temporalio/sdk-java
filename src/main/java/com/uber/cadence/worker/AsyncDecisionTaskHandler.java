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
package com.uber.cadence.worker;

import com.uber.cadence.Decision;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.WorkflowType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

public class AsyncDecisionTaskHandler extends DecisionTaskHandler {

    private static final Log log = LogFactory.getLog(AsyncDecisionTaskHandler.class);

    private static final Log asyncThreadDumpLog = LogFactory.getLog(AsyncDecisionTaskHandler.class.getName()
            + ".waitingTasksStacks");

    private final AsyncWorkflowFactory asyncWorkflowFactory;

    public AsyncDecisionTaskHandler(AsyncWorkflowFactory asyncWorkflowFactory) {
        this.asyncWorkflowFactory = asyncWorkflowFactory;
    }

    @Override
    public RespondDecisionTaskCompletedRequest handleDecisionTask(DecisionTaskWithHistoryIterator decisionTaskIterator) throws Exception {
        HistoryHelper historyHelper = new HistoryHelper(decisionTaskIterator);
        AsyncDecider decider = createDecider(historyHelper);
        decider.decide();
        DecisionsHelper decisionsHelper = decider.getDecisionsHelper();
        List<Decision> decisions = decisionsHelper.getDecisions();
        byte[] context = decisionsHelper.getWorkflowContextDataToReturn();
        PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
        if (log.isDebugEnabled()) {
            log.debug("WorkflowTask taskId=" + decisionTask.getStartedEventId() + ", taskToken=" + decisionTask.getTaskToken()
                    + " completed with " + decisions.size() + " new decisions");
        }
        if (decisions.size() == 0 && asyncThreadDumpLog.isTraceEnabled()) {
            asyncThreadDumpLog.trace("Empty decision list with the following waiting tasks:\n"
                    + decider.getAsynchronousThreadDumpAsString());
        }
        RespondDecisionTaskCompletedRequest completedRequest = new RespondDecisionTaskCompletedRequest();
        completedRequest.setTaskToken(decisionTask.getTaskToken());
        completedRequest.setDecisions(decisions);
        completedRequest.setExecutionContext(context);
        return completedRequest;
    }

//    @Override
//    public WorkflowDefinition loadWorkflowThroughReplay(DecisionTaskWithHistoryIterator decisionTaskIterator) throws Exception {
//        // TODO(Cadence): Decide if needed in this form.
//        throw new UnsupportedOperationException("not impelemented");
//        HistoryHelper historyHelper = new HistoryHelper(decisionTaskIterator);
//        AsyncDecider decider = createDecider(historyHelper);
//        decider.decide();
//        DecisionsHelper decisionsHelper = decider.getDecisionsHelper();
//        if (decisionsHelper.isWorkflowFailed()) {
//            throw new IllegalStateException("Cannot load failed workflow", decisionsHelper.getWorkflowFailureCause());
//        }
//        return decider.getWorkflowDefinition();
//    }

    @Override
    public String getAsynchronousThreadDump(DecisionTaskWithHistoryIterator decisionTaskIterator) throws Exception {
        HistoryHelper historyHelper = new HistoryHelper(decisionTaskIterator);
        AsyncDecider decider = createDecider(historyHelper);
        decider.decide();
        return decider.getAsynchronousThreadDumpAsString();
    }

    private AsyncDecider createDecider(HistoryHelper historyHelper) throws Exception {
        PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
        WorkflowType workflowType = decisionTask.getWorkflowType();
        DecisionsHelper decisionsHelper = new DecisionsHelper(decisionTask);
        AsyncDecider decider = new AsyncDecider(asyncWorkflowFactory.getWorkflow(workflowType), historyHelper, decisionsHelper);
        return decider;
    }
}
