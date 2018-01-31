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
package com.uber.cadence;

import com.uber.cadence.generic.GenericAsyncActivityClient;
import com.uber.cadence.generic.GenericAsyncWorkflowClient;
import com.uber.cadence.workflow.WorkflowContext;

/**
 * Represents the context for decider. Should only be used within the scope of
 * workflow definition code, meaning any code which is not part of activity
 * implementations.
 */
public abstract class AsyncDecisionContext {

    public abstract GenericAsyncActivityClient getActivityClient();

    public abstract GenericAsyncWorkflowClient getWorkflowClient();

    public abstract AsyncWorkflowClock getWorkflowClock();

    public abstract WorkflowContext getWorkflowContext();

}
