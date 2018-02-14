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

import com.uber.cadence.ActivityType;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.ActivityTask;

final class ActivityTaskImpl implements ActivityTask {
    private final PollForActivityTaskResponse response;

    public ActivityTaskImpl(PollForActivityTaskResponse response) {
        this.response = response;
    }

    @Override
    public byte[] getTaskToken() {
        return response.getTaskToken();
    }

    @Override
    public WorkflowExecution getWorkflowExecution() {
        return response.getWorkflowExecution();
    }

    @Override
    public String getActivityId() {
        return response.getActivityId();
    }

    @Override
    public ActivityType getActivityType() {
        return response.getActivityType();
    }

    @Override
    public long getScheduledTimestamp() {
        return response.getScheduledTimestamp();
    }

    @Override
    public int getScheduleToCloseTimeoutSeconds() {
        return response.getScheduleToCloseTimeoutSeconds();
    }

    @Override
    public void setScheduleToCloseTimeoutSecondsIsSet(boolean value) {
        response.setScheduleToCloseTimeoutSecondsIsSet(value);
    }

    @Override
    public int getStartToCloseTimeoutSeconds() {
        return response.getStartToCloseTimeoutSeconds();
    }

    @Override
    public int getHeartbeatTimeoutSeconds() {
        return response.getHeartbeatTimeoutSeconds();
    }

    public byte[] getInput() {
        return response.getInput();
    }
}
