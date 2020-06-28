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

import io.temporal.common.v1.Payloads;
import io.temporal.internal.common.OptionsUtils;

public final class ContinueAsNewWorkflowExecutionParameters {

  private int workflowRunTimeoutSeconds;
  private Payloads input;
  private String taskQueue;
  private int workflowTaskTimeoutSeconds;
  private String workflowType;

  public void setWorkflowType(String workflowType) {
    this.workflowType = workflowType;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public int getWorkflowRunTimeoutSeconds() {
    return workflowRunTimeoutSeconds;
  }

  public void setWorkflowRunTimeoutSeconds(int workflowRunTimeoutSeconds) {
    this.workflowRunTimeoutSeconds = workflowRunTimeoutSeconds;
  }

  public ContinueAsNewWorkflowExecutionParameters withWorkflowRunTimeoutSeconds(
      int workflowRunTimeoutSeconds) {
    this.workflowRunTimeoutSeconds = workflowRunTimeoutSeconds;
    return this;
  }

  public ContinueAsNewWorkflowExecutionParameters withInput(Payloads input) {
    this.input = input;
    return this;
  }

  public Payloads getInput() {
    return input;
  }

  public void setInput(Payloads input) {
    this.input = input;
  }

  public String getTaskQueue() {
    return OptionsUtils.safeGet(taskQueue);
  }

  public void setTaskQueue(String taskQueue) {
    this.taskQueue = taskQueue;
  }

  public ContinueAsNewWorkflowExecutionParameters withTaskQueue(String taskQueue) {
    this.taskQueue = taskQueue;
    return this;
  }

  public int getWorkflowTaskTimeoutSeconds() {
    return workflowTaskTimeoutSeconds;
  }

  public void setWorkflowTaskTimeoutSeconds(int workflowTaskTimeoutSeconds) {
    this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
  }

  public ContinueAsNewWorkflowExecutionParameters withWorkflowTaskTimeoutSeconds(
      int workflowTaskTimeoutSeconds) {
    this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("Input: " + String.valueOf(input).substring(0, 512) + ", ");
    sb.append("WorkflowRunTimeout: " + workflowRunTimeoutSeconds + ", ");
    sb.append("WorkflowTaskTimeout: " + workflowTaskTimeoutSeconds + ", ");
    sb.append("TaskQueue: " + taskQueue + ", ");
    sb.append("}");
    return sb.toString();
  }

  public ContinueAsNewWorkflowExecutionParameters copy() {
    ContinueAsNewWorkflowExecutionParameters result =
        new ContinueAsNewWorkflowExecutionParameters();
    result.setWorkflowRunTimeoutSeconds(workflowRunTimeoutSeconds);
    result.setInput(input);
    result.setTaskQueue(taskQueue);
    result.setWorkflowTaskTimeoutSeconds(workflowTaskTimeoutSeconds);
    return result;
  }
}
