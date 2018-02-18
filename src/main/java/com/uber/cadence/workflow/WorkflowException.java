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

import com.uber.cadence.WorkflowExecution;

/**
 * Base exception for all workflow failures returned by an external client.
 * Note that inside a workflow implementation child workflows throw subclasses of {@link ChildWorkflowException}.
 */
public class WorkflowException extends RuntimeException {

    private final WorkflowExecution execution;
    private final String workflowType;

    protected WorkflowException(String message, WorkflowExecution execution, String workflowType, Throwable cause) {
        super(message, cause);
        this.execution = execution;
        this.workflowType = workflowType;
    }

    public WorkflowExecution getExecution() {
        return execution;
    }

    public String getWorkflowType() {
        return workflowType;
    }
}
