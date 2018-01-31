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
package com.uber.cadence.generic;

import com.uber.cadence.activity.ActivityExecutionContext;
import com.uber.cadence.activity.ActivityFailureException;
import com.uber.cadence.ActivityTask;
import com.uber.cadence.worker.ActivityTypeExecutionOptions;

import java.util.concurrent.CancellationException;

/**
 * Extend this class to implement an activity. There are two types of activity
 * implementation: synchronous and asynchronous. Synchronous ties thread that
 * calls {@link #execute(ActivityExecutionContext)} method.
 **
 * @author fateev
 */
public abstract class ActivityImplementationBase extends ActivityImplementation {

    /**
     * @see ActivityImplementation#execute(ActivityExecutionContext)
     */
    @Override
    public byte[] execute(ActivityExecutionContext context)
            throws ActivityFailureException, CancellationException {
        ActivityTask task = context.getTask();
        return execute(task.getInput(), context);
    }

    @Override
    public ActivityTypeExecutionOptions getExecutionOptions() {
        return new ActivityTypeExecutionOptions();
    }

    /**
     * Execute activity.
     * 
     * @see ActivityTypeExecutionOptions#isManualActivityCompletion()
     * 
     * @param input
     *            activity input.
     * @return result of activity execution
     * @throws ActivityFailureException
     *             any other exception is converted to status, reason and
     *             details using DataConverter
     */

    protected abstract byte[] execute(byte[] input, ActivityExecutionContext context)
            throws ActivityFailureException, CancellationException;

}
