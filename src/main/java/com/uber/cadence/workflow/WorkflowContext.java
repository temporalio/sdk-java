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
package com.uber.cadence.workflow;

public interface WorkflowContext {

    com.uber.cadence.WorkflowExecution getWorkflowExecution();

    // TODO: Add to Cadence
//    com.uber.cadence.WorkflowExecution getParentWorkflowExecution();
    
    com.uber.cadence.WorkflowType getWorkflowType();
    
    boolean isCancelRequested();
    
    ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion();
    
    void setContinueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters);

//    com.uber.cadence.ChildPolicy getChildPolicy();

//    String getContinuedExecutionRunId();

    int getExecutionStartToCloseTimeoutSeconds();

    int getDecisionTaskTimeoutSeconds();

    String getTaskList();

    String getDomain();
}
