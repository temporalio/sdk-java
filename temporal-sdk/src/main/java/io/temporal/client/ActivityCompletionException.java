/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.failure.TemporalException;
import java.util.Optional;

/** Base exception for all failures returned by an activity completion client. Do not extend! */
public class ActivityCompletionException extends TemporalException {

  private final String workflowId;

  private final String runId;

  private final String activityType;

  private final String activityId;

  protected ActivityCompletionException(ActivityInfo info) {
    this(info, null);
  }

  protected ActivityCompletionException(ActivityInfo info, Throwable cause) {
    super(
        info != null
            ? "WorkflowId="
                + info.getWorkflowId()
                + ", RunId="
                + info.getRunId()
                + ", ActivityType="
                + info.getActivityType()
                + ", ActivityId="
                + info.getActivityId()
            : null,
        cause);
    if (info != null) {
      workflowId = info.getWorkflowId();
      runId = info.getRunId();
      activityType = info.getActivityType();
      activityId = info.getActivityId();
    } else {
      this.workflowId = null;
      this.runId = null;
      activityType = null;
      activityId = null;
    }
  }

  protected ActivityCompletionException(String activityId, Throwable cause) {
    super("ActivityId=" + activityId, cause);
    this.workflowId = null;
    this.runId = null;
    this.activityType = null;
    this.activityId = activityId;
  }

  protected ActivityCompletionException(Throwable cause) {
    this((ActivityInfo) null, cause);
  }

  protected ActivityCompletionException() {
    super(null, null);
    workflowId = null;
    runId = null;
    activityType = null;
    activityId = null;
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getWorkflowId() {
    return Optional.ofNullable(workflowId);
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getRunId() {
    return Optional.ofNullable(runId);
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getActivityType() {
    return Optional.ofNullable(activityType);
  }

  /** Optional as it might be not known to the exception source. */
  public Optional<String> getActivityId() {
    return Optional.ofNullable(activityId);
  }
}
