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

@SuppressWarnings("serial")
public class StartChildWorkflowFailedException extends ChildWorkflowException {

    private ChildWorkflowExecutionFailedCause failureCause;
    
    public StartChildWorkflowFailedException(String message) {
        super(message);
    }

    public StartChildWorkflowFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public StartChildWorkflowFailedException(long eventId, WorkflowExecution workflowExecution, WorkflowType workflowType,
                                             ChildWorkflowExecutionFailedCause cause) {
        super(String.valueOf(cause), eventId, workflowExecution, workflowType);
        this.failureCause = cause;
    }

    /**
     * @return enumeration that contains the cause of the failure
     */
    public ChildWorkflowExecutionFailedCause getFailureCause() {
        return failureCause;
    }

    public void setFailureCause(ChildWorkflowExecutionFailedCause failureCause) {
        this.failureCause = failureCause;
    }

}
