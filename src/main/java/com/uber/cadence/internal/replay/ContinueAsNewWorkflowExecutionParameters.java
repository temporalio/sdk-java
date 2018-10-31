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

package com.uber.cadence.internal.replay;

import java.nio.charset.StandardCharsets;

public final class ContinueAsNewWorkflowExecutionParameters {

  private int executionStartToCloseTimeoutSeconds;
  private byte[] input;
  private String taskList;
  private int taskStartToCloseTimeoutSeconds;
  private String workflowType;

  public void setWorkflowType(String workflowType) {
    this.workflowType = workflowType;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public int getExecutionStartToCloseTimeoutSeconds() {
    return executionStartToCloseTimeoutSeconds;
  }

  public void setExecutionStartToCloseTimeoutSeconds(int executionStartToCloseTimeoutSeconds) {
    this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
  }

  public ContinueAsNewWorkflowExecutionParameters withExecutionStartToCloseTimeoutSeconds(
      int executionStartToCloseTimeoutSeconds) {
    this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
    return this;
  }

  public ContinueAsNewWorkflowExecutionParameters withInput(byte[] input) {
    this.input = input;
    return this;
  }

  public byte[] getInput() {
    return input;
  }

  public void setInput(byte[] input) {
    this.input = input;
  }

  public String getTaskList() {
    return taskList;
  }

  public void setTaskList(String taskList) {
    this.taskList = taskList;
  }

  public ContinueAsNewWorkflowExecutionParameters withTaskList(String taskList) {
    this.taskList = taskList;
    return this;
  }

  public int getTaskStartToCloseTimeoutSeconds() {
    return taskStartToCloseTimeoutSeconds;
  }

  public void setTaskStartToCloseTimeoutSeconds(int taskStartToCloseTimeoutSeconds) {
    this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
  }

  public ContinueAsNewWorkflowExecutionParameters withTaskStartToCloseTimeoutSeconds(
      int taskStartToCloseTimeoutSeconds) {
    this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("Input: " + new String(input, 0, 512, StandardCharsets.UTF_8) + ", ");
    sb.append("ExecutionStartToCloseTimeout: " + executionStartToCloseTimeoutSeconds + ", ");
    sb.append("TaskStartToCloseTimeout: " + taskStartToCloseTimeoutSeconds + ", ");
    sb.append("TaskList: " + taskList + ", ");
    sb.append("}");
    return sb.toString();
  }

  public ContinueAsNewWorkflowExecutionParameters copy() {
    ContinueAsNewWorkflowExecutionParameters result =
        new ContinueAsNewWorkflowExecutionParameters();
    result.setExecutionStartToCloseTimeoutSeconds(executionStartToCloseTimeoutSeconds);
    result.setInput(input);
    result.setTaskList(taskList);
    result.setTaskStartToCloseTimeoutSeconds(taskStartToCloseTimeoutSeconds);
    return result;
  }
}
