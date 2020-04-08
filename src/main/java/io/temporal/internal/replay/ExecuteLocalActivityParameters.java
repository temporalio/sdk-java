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

package io.temporal.internal.replay;

import io.temporal.common.RetryOptions;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.execution.WorkflowExecution;
import java.util.Arrays;

public class ExecuteLocalActivityParameters {

  private String workflowNamespace;
  private WorkflowExecution workflowExecution;
  private String activityId;
  private ActivityType activityType;
  private byte[] input;
  private long scheduleToCloseTimeoutSeconds;
  private RetryOptions retryOptions;
  private long elapsedTime;
  private int attempt;

  public ExecuteLocalActivityParameters() {}

  /**
   * Returns the value of the ActivityType property for this object.
   *
   * @return The value of the ActivityType property for this object.
   */
  public ActivityType getActivityType() {
    return activityType;
  }

  /**
   * Sets the value of the ActivityType property for this object.
   *
   * @param activityType The new value for the ActivityType property for this object.
   */
  public void setActivityType(ActivityType activityType) {
    this.activityType = activityType;
  }

  /**
   * Sets the value of the ActivityType property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * @param activityType The new value for the ActivityType property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public ExecuteLocalActivityParameters withActivityType(ActivityType activityType) {
    this.activityType = activityType;
    return this;
  }

  /**
   * Returns the value of the ActivityId property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @return The value of the ActivityId property for this object.
   */
  public String getActivityId() {
    return activityId;
  }

  /**
   * Sets the value of the ActivityId property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @param activityId The new value for the ActivityId property for this object.
   */
  public void setActivityId(String activityId) {
    this.activityId = activityId;
  }

  /**
   * Sets the value of the ActivityId property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @param activityId The new value for the ActivityId property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public ExecuteLocalActivityParameters withActivityId(String activityId) {
    this.activityId = activityId;
    return this;
  }

  /**
   * Returns the value of the Input property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 100000<br>
   *
   * @return The value of the Input property for this object.
   */
  public byte[] getInput() {
    return input;
  }

  /**
   * Sets the value of the Input property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 100000<br>
   *
   * @param input The new value for the Input property for this object.
   */
  public void setInput(byte[] input) {
    this.input = input;
  }

  /**
   * Sets the value of the Input property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 100000<br>
   *
   * @param input The new value for the Input property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public ExecuteLocalActivityParameters withInput(byte[] input) {
    this.input = input;
    return this;
  }

  /**
   * Returns the value of the ScheduleToCloseTimeout property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @return The value of the ScheduleToCloseTimeout property for this object.
   */
  public long getScheduleToCloseTimeoutSeconds() {
    return scheduleToCloseTimeoutSeconds;
  }

  /**
   * Sets the value of the ScheduleToCloseTimeout property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @param scheduleToCloseTimeoutSeconds The new value for the ScheduleToCloseTimeout property for
   *     this object.
   */
  public void setScheduleToCloseTimeoutSeconds(long scheduleToCloseTimeoutSeconds) {
    this.scheduleToCloseTimeoutSeconds = scheduleToCloseTimeoutSeconds;
  }

  /**
   * Sets the value of the ScheduleToCloseTimeout property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @param scheduleToCloseTimeoutSeconds The new value for the ScheduleToCloseTimeout property for
   *     this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public ExecuteLocalActivityParameters withScheduleToCloseTimeoutSeconds(
      long scheduleToCloseTimeoutSeconds) {
    this.scheduleToCloseTimeoutSeconds = scheduleToCloseTimeoutSeconds;
    return this;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public void setRetryOptions(RetryOptions retryOptions) {
    this.retryOptions = retryOptions;
  }

  public long getElapsedTime() {
    return elapsedTime;
  }

  public void setElapsedTime(long startTime) {
    this.elapsedTime = startTime;
  }

  public String getWorkflowNamespace() {
    return workflowNamespace;
  }

  public void setWorkflowNamespace(String workflowNamespace) {
    this.workflowNamespace = workflowNamespace;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  public void setWorkflowExecution(WorkflowExecution workflowExecution) {
    this.workflowExecution = workflowExecution;
  }

  @Override
  public String toString() {
    return "ExecuteLocalActivityParameters{"
        + "workflowNamespace='"
        + workflowNamespace
        + '\''
        + ", workflowExecution="
        + workflowExecution
        + ", activityId='"
        + activityId
        + '\''
        + ", activityType="
        + activityType
        + ", input="
        + Arrays.toString(input)
        + ", scheduleToCloseTimeoutSeconds="
        + scheduleToCloseTimeoutSeconds
        + ", retryOptions="
        + retryOptions
        + ", elapsedTime="
        + elapsedTime
        + ", attempt="
        + attempt
        + '}';
  }
}
