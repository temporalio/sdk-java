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

import com.uber.cadence.AsyncDecisionContext;
import com.uber.cadence.AsyncWorkflowClock;
import com.uber.cadence.workflow.WorkflowContext;
import com.uber.cadence.generic.GenericAsyncActivityClient;
import com.uber.cadence.generic.GenericAsyncWorkflowClient;

class AsyncDecisionContextImpl extends AsyncDecisionContext {

    private final GenericAsyncActivityClient activityClient;

    private final GenericAsyncWorkflowClient workflowClient;

    private final AsyncWorkflowClock workflowClock;

    private final WorkflowContext workflowContext;

    AsyncDecisionContextImpl(GenericAsyncActivityClient activityClient, GenericAsyncWorkflowClient workflowClient,
                             AsyncWorkflowClock workflowClock, WorkflowContext workflowContext) {
        this.activityClient = activityClient;
        this.workflowClient = workflowClient;
        this.workflowClock = workflowClock;
        this.workflowContext = workflowContext;
    }

    @Override
    public GenericAsyncActivityClient getActivityClient() {
       return activityClient;
    }

    @Override
    public GenericAsyncWorkflowClient getWorkflowClient() {
        return workflowClient;
    }

    @Override
    public AsyncWorkflowClock getWorkflowClock() {
        return workflowClock;
    }

    @Override
    public WorkflowContext getWorkflowContext() {
        return workflowContext;
    }
}
