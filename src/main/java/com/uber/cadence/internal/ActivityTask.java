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

import com.uber.cadence.ActivityType;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.WorkflowExecution;

public final class ActivityTask {
    private final PollForActivityTaskResponse response;

    public ActivityTask(PollForActivityTaskResponse response) {
        this.response = response;
    }

    public byte[] getTaskToken() {
        return response.getTaskToken();
    }

    public WorkflowExecution getWorkflowExecution() {
        return response.getWorkflowExecution();
    }

    public String getActivityId() {
        return response.getActivityId();
    }

    public ActivityType getActivityType() {
        return response.getActivityType();
    }

    public long getScheduledTimestamp() {
        return response.getScheduledTimestamp();
    }

    public int getScheduleToCloseTimeoutSeconds() {
        return response.getScheduleToCloseTimeoutSeconds();
    }

    public void setScheduleToCloseTimeoutSecondsIsSet(boolean value) {
        response.setScheduleToCloseTimeoutSecondsIsSet(value);
    }

    public int getStartToCloseTimeoutSeconds() {
        return response.getStartToCloseTimeoutSeconds();
    }

    public int getHeartbeatTimeoutSeconds() {
        return response.getHeartbeatTimeoutSeconds();
    }

    public byte[] getInput() {
        return response.getInput();
    }
}
