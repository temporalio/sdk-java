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

/** Exception used to communicate failure of a remote activity. */
@SuppressWarnings("serial")
public abstract class ActivityException extends WorkflowOperationException {

  private final ActivityType activityType;

  private final String activityId;

  ActivityException(String message, long eventId, ActivityType activityType, String activityId) {
    super(
        message
            + ", ActivityType=\""
            + activityType.getName()
            + "\", ActivityID=\""
            + activityId
            + "\", EventID="
            + eventId,
        eventId);
    this.activityType = activityType;
    this.activityId = activityId;
  }

  public ActivityType getActivityType() {
    return activityType;
  }

  public String getActivityId() {
    return activityId;
  }
}
