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
import com.uber.cadence.TimeoutType;

/**
 * Exception that indicates Activity time out.
 */
@SuppressWarnings("serial")
public class ActivityTaskTimedOutException extends ActivityTaskException {

    private TimeoutType timeoutType;

    private byte[] details;

    public ActivityTaskTimedOutException(String message, Throwable cause) {
        super(message, cause);
    }

    public ActivityTaskTimedOutException(String message) {
        super(message);
    }

    public ActivityTaskTimedOutException(long eventId, ActivityType activityType, String activityId, TimeoutType timeoutType,
                                         byte[] details) {
        super(String.valueOf(timeoutType), eventId, activityType, activityId);
        this.timeoutType = timeoutType;
        this.details = details;
    }

    public TimeoutType getTimeoutType() {
        return timeoutType;
    }

    public void setTimeoutType(TimeoutType timeoutType) {
        this.timeoutType = timeoutType;
    }

    /**
     * @return The value from the last activity heartbeat details field.
     */
    public byte[] getDetails() {
        return details;
    }

    public void setDetails(byte[] details) {
        this.details = details;
    }

}
