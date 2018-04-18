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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.ActivityTask;

/** Base exception for all failures returned by an activity completion client. */
public class ActivityCompletionException extends RuntimeException {

  private final WorkflowExecution execution;

  private final String activityType;

  private final String activityId;

  protected ActivityCompletionException(ActivityTask task) {
    execution = task.getWorkflowExecution();
    activityType = task.getActivityType();
    activityId = task.getActivityId();
  }

  protected ActivityCompletionException(ActivityTask task, Throwable cause) {
    super(
        task != null
            ? "Execution="
                + task.getWorkflowExecution()
                + ", ActivityType="
                + task.getActivityType()
                + ", ActivityID="
                + task.getActivityId()
            : null,
        cause);
    if (task != null) {
      execution = task.getWorkflowExecution();
      activityType = task.getActivityType();
      activityId = task.getActivityId();
    } else {
      execution = null;
      activityType = null;
      activityId = null;
    }
  }

  protected ActivityCompletionException(String activityId, Throwable cause) {
    super("ActivityId" + activityId, cause);
    this.execution = null;
    this.activityType = null;
    this.activityId = activityId;
  }

  protected ActivityCompletionException(Throwable cause) {
    this((ActivityTask) null, cause);
  }

  protected ActivityCompletionException() {
    super();
    execution = null;
    activityType = null;
    activityId = null;
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  public String getActivityType() {
    return activityType;
  }

  public String getActivityId() {
    return activityId;
  }
}
