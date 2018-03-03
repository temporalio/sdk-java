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

import com.uber.cadence.ActivityType;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.converter.DataConverter;

/**
 * Exception that indicates Activity that activity timed out.
 * If timeout type is {@link TimeoutType#HEARTBEAT} then {@link #getDetails(Class)} returns the value
 * passed to the latest successful {@link com.uber.cadence.activity.Activity#heartbeat(Object)} call.
 */
@SuppressWarnings("serial")
public final class ActivityTimeoutException extends ActivityException {

    private final TimeoutType timeoutType;

    private final byte[] details;
    private final DataConverter dataConverter;

    public ActivityTimeoutException(long eventId, ActivityType activityType, String activityId, TimeoutType timeoutType,
                                    byte[] details, DataConverter dataConverter) {
        super(String.valueOf(timeoutType) + " timeout", eventId, activityType, activityId);
        this.timeoutType = timeoutType;
        this.details = details;
        this.dataConverter = dataConverter;
    }

    public TimeoutType getTimeoutType() {
        return timeoutType;
    }

    /**
     * @return The value from the last activity heartbeat details field.
     */
    public <V> V getDetails(Class<V> detailsClass) {
        return dataConverter.fromData(details, detailsClass);
    }
}
