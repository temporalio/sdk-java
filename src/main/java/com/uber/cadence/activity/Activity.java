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
package com.uber.cadence.activity;

import com.uber.cadence.WorkflowService;
import com.uber.cadence.internal.worker.ActivityInternal;

import java.util.concurrent.CancellationException;

/**
 * Use this method from within activity implementation to get information about activity task
 * and heartbeat.
 */
public final class Activity {

    /**
     * @return task token that is required to report task completion when
     * manual activity completion is used.
     */
    public static byte[] getTaskToken() {
        return ActivityInternal.getTask().getTaskToken();
    }

    /**
     * @return workfow execution that requested the activity execution
     */
    public static com.uber.cadence.WorkflowExecution getWorkflowExecution() {
        return ActivityInternal.getTask().getWorkflowExecution();
    }

    /**
     * @return task that caused activity execution
     */
    public static ActivityTask getTask() {
        return ActivityInternal.getTask();
    }

    /**
     * Use to notify Cadence service that activity execution is alive.
     *
     * @param args In case of activity timeout details are returned as a field of
     *             the exception thrown.
     * @throws CancellationException Indicates that activity cancellation was requested by the
     *                               workflow.Should be rethrown from activity implementation to
     *                               indicate successful cancellation.
     */
    public static void heartbeat(Object... args)
            throws CancellationException {
        ActivityInternal.recordActivityHeartbeat(args);
    }

    /**
     * @return an instance of the Simple Workflow Java client that is the same
     * used by the invoked activity worker.
     */
    public static WorkflowService.Iface getService() {
        return ActivityInternal.getService();
    }

    public static String getDomain() {
        return ActivityInternal.getDomain();
    }

    /**
     * Prohibit instantiation
     */
    private Activity() {
    }
}
