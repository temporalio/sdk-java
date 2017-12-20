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

import com.uber.cadence.generic.ActivityImplementation;
import com.uber.cadence.WorkflowService.Iface;

import java.util.concurrent.CancellationException;

/**
 * Context object passed to an activity implementation.
 * 
 * @see ActivityImplementation
 * 
 * @author fateev
 */
public abstract class ActivityExecutionContext {

    /**
     * @return task token that is required to report task completion when
     *         manual activity completion is used.
     */
    public abstract byte[] getTaskToken();

    /**
     * @return workfow execution that requested the activity execution
     */
    public abstract com.uber.cadence.WorkflowExecution getWorkflowExecution();

    /**
     * @return task that caused activity execution
     */
    public abstract ActivityTask getTask();

    /**
     * Use to notify Simple Workflow that activity execution is alive.
     * 
     * @param details
     *            In case of activity timeout details are returned as a field of
     *            the exception thrown.
     * @throws CancellationException
     *             Indicates that activity cancellation was requested by the
     *             workflow.Should be rethrown from activity implementation to
     *             indicate successful cancellation.
     */
    public abstract void recordActivityHeartbeat(byte[] details)
            throws CancellationException;

    /**
     * @return an instance of the Simple Workflow Java client that is the same
     *         used by the invoked activity worker.
     */
    public abstract Iface getService();
    
    public String getDomain() {
        // Throwing implementation is provided to not break existing subclasses
        throw new UnsupportedOperationException();
    }
}
