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

import com.uber.cadence.WorkflowService;
import com.uber.cadence.activity.ActivityTask;

/**
 * Base class for activity implementation.
 * Internal.
 * 
 * @author fateev, suskin
 */
public interface ActivityImplementation {

    ActivityTypeExecutionOptions getExecutionOptions();

    /**
     * Execute external activity or initiate its execution if
     * {@link ActivityTypeExecutionOptions#isDoNotCompleteOnReturn()} is <code>true</code>.
     * 
     * @return result of activity execution if {@link ActivityTypeExecutionOptions#isDoNotCompleteOnReturn()} is set
     *         to false.
     */
    byte[] execute(WorkflowService.Iface service, String domain, ActivityTask task);
}
