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
package com.uber.cadence.internal.worker;

import com.uber.cadence.workflow.WorkflowContext;
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;

class WorkfowContextImpl implements WorkflowContext {

    private final PollForDecisionTaskResponse decisionTask;
    private boolean cancelRequested;
    private ContinueAsNewWorkflowExecutionParameters continueAsNewOnCompletion;
    
    public WorkfowContextImpl(PollForDecisionTaskResponse decisionTask) {
        this.decisionTask = decisionTask;
    }
    
    @Override
    public com.uber.cadence.WorkflowExecution getWorkflowExecution() {
        return decisionTask.getWorkflowExecution();
    }

    @Override
    public com.uber.cadence.WorkflowType getWorkflowType() {
        return decisionTask.getWorkflowType();
    }

    @Override
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    void setCancelRequested(boolean flag) {
        cancelRequested = flag;
    }

    @Override
    public ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion() {
        return continueAsNewOnCompletion;
    }

    @Override
    public void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {
        this.continueAsNewOnCompletion = continueParameters;
    }

//    @Override
//    public WorkflowExecution getParentWorkflowExecution() {
//        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
//        return attributes.getParentWorkflowExecution();
//    }

//    @Override
//    public List<String> getTagList() {
//        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
//        return attributes.getTagList();
//    }

//    @Override
//    public com.uber.cadence.ChildPolicy getChildPolicy() {
//        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
//        return ChildPolicy.fromValue(attributes.getChildPolicy());
//    }
    
//    @Override
//    public String getContinuedExecutionRunId() {
//        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
//        return attributes.getContinuedExecutionRunId();
//    }
    
    @Override
    public int getExecutionStartToCloseTimeoutSeconds() {
        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
        return attributes.getExecutionStartToCloseTimeoutSeconds();
    }
    
    @Override
    public String getTaskList() {
        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
        return attributes.getTaskList().getName();
    }

//    @Override
//    public String getLambdaRole() {
//        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
//        return attributes.getLambdaRole();
//    }

    private WorkflowExecutionStartedEventAttributes getWorkflowStartedEventAttributes() {
        HistoryEvent firstHistoryEvent = decisionTask.getHistory().getEvents().get(0);
        WorkflowExecutionStartedEventAttributes attributes = firstHistoryEvent.getWorkflowExecutionStartedEventAttributes();
        return attributes;
    }

//    @Override
//    public int getTaskPriority() {
//        WorkflowExecutionStartedEventAttributes attributes = getWorkflowStartedEventAttributes();
//        String result = attributes.getTaskPriority();
//        return FlowHelpers.taskPriorityToInt(result);
//    }
}
