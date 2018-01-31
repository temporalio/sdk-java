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
package com.uber.cadence.internal;

import com.uber.cadence.WorkflowExecution;

/**
 * Exception used to communicate failure of a signal.
 */
@SuppressWarnings("serial")
public class SignalExternalWorkflowException extends DecisionException {

//    private SignalExternalWorkflowExecutionFailedCause failureCause;

    private String failureCause;

    private WorkflowExecution signaledExecution;
    
    public SignalExternalWorkflowException(String message) {
        super(message);
    }

    public SignalExternalWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }

    public SignalExternalWorkflowException(long eventId, WorkflowExecution signaledExecution, String cause) {
        super(cause + " for signaledExecution=\"" + signaledExecution, eventId);
        this.signaledExecution = signaledExecution;
        this.failureCause = cause;
    }

    public WorkflowExecution getSignaledExecution() {
        return signaledExecution;
    }
    
    public void setFailureCause(String failureCause) {
        this.failureCause = failureCause;
    }

    public String getFailureCause() {
        return failureCause;
    }
    
    public void setSignaledExecution(WorkflowExecution signaledExecution) {
        this.signaledExecution = signaledExecution;
    }
}
