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

package com.uber.cadence.internal.common;

import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.common.RetryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StartWorkflowExecutionParameters {

  private String workflowId;

  private WorkflowType workflowType;

  private String taskList;

  private byte[] input;

  private long executionStartToCloseTimeoutSeconds;

  private long taskStartToCloseTimeoutSeconds;

  private WorkflowIdReusePolicy workflowIdReusePolicy;

  private RetryParameters retryParameters;

  private String cronSchedule;

  private Map<String, byte[]> memo;

  private Map<String, byte[]> searchAttributes;

  private Map<String, byte[]> context;

  /**
   * Returns the value of the WorkflowId property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @return The value of the WorkflowId property for this object.
   */
  public String getWorkflowId() {
    return workflowId;
  }

  /**
   * Sets the value of the WorkflowId property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @param workflowId The new value for the WorkflowId property for this object.
   */
  public void setWorkflowId(String workflowId) {
    this.workflowId = workflowId;
  }

  /**
   * Sets the value of the WorkflowId property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>1 - 64<br>
   *
   * @param workflowId The new value for the WorkflowId property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public StartWorkflowExecutionParameters withWorkflowId(String workflowId) {
    this.workflowId = workflowId;
    return this;
  }

  /**
   * Returns the value of the WorkflowType property for this object.
   *
   * @return The value of the WorkflowType property for this object.
   */
  public WorkflowType getWorkflowType() {
    return workflowType;
  }

  /**
   * Sets the value of the WorkflowType property for this object.
   *
   * @param workflowType The new value for the WorkflowType property for this object.
   */
  public void setWorkflowType(WorkflowType workflowType) {
    this.workflowType = workflowType;
  }

  /**
   * Sets the value of the WorkflowType property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * @param workflowType The new value for the WorkflowType property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public StartWorkflowExecutionParameters withWorkflowType(WorkflowType workflowType) {
    this.workflowType = workflowType;
    return this;
  }

  public WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
    return workflowIdReusePolicy;
  }

  public void setWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
    this.workflowIdReusePolicy = workflowIdReusePolicy;
  }

  public StartWorkflowExecutionParameters withWorkflowIdReusePolicy(
      WorkflowIdReusePolicy workflowIdReusePolicy) {
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    return this;
  }

  /**
   * Returns the value of the TaskList property for this object.
   *
   * @return The value of the TaskList property for this object.
   */
  public String getTaskList() {
    return taskList;
  }

  /**
   * Sets the value of the TaskList property for this object.
   *
   * @param taskList The new value for the TaskList property for this object.
   */
  public void setTaskList(String taskList) {
    this.taskList = taskList;
  }

  /**
   * Sets the value of the TaskList property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * @param taskList The new value for the TaskList property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public StartWorkflowExecutionParameters withTaskList(String taskList) {
    this.taskList = taskList;
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
  public StartWorkflowExecutionParameters withInput(byte[] input) {
    this.input = input;
    return this;
  }

  /**
   * Returns the value of the StartToCloseTimeout property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 64<br>
   *
   * @return The value of the StartToCloseTimeout property for this object.
   */
  public long getExecutionStartToCloseTimeoutSeconds() {
    return executionStartToCloseTimeoutSeconds;
  }

  /**
   * Sets the value of the StartToCloseTimeout property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 64<br>
   *
   * @param executionStartToCloseTimeoutSeconds The new value for the StartToCloseTimeout property
   *     for this object.
   */
  public void setExecutionStartToCloseTimeoutSeconds(long executionStartToCloseTimeoutSeconds) {
    this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
  }

  /**
   * Sets the value of the StartToCloseTimeout property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 64<br>
   *
   * @param executionStartToCloseTimeoutSeconds The new value for the StartToCloseTimeout property
   *     for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public StartWorkflowExecutionParameters withExecutionStartToCloseTimeoutSeconds(
      long executionStartToCloseTimeoutSeconds) {
    this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
    return this;
  }

  public long getTaskStartToCloseTimeoutSeconds() {
    return taskStartToCloseTimeoutSeconds;
  }

  public void setTaskStartToCloseTimeoutSeconds(long taskStartToCloseTimeoutSeconds) {
    this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
  }

  public StartWorkflowExecutionParameters withTaskStartToCloseTimeoutSeconds(
      int taskStartToCloseTimeoutSeconds) {
    this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    return this;
  }

  public RetryParameters getRetryParameters() {
    return retryParameters;
  }

  public void setRetryParameters(RetryParameters retryParameters) {
    this.retryParameters = retryParameters;
  }

  public String getCronSchedule() {
    return cronSchedule;
  }

  public void setCronSchedule(String cronSchedule) {
    this.cronSchedule = cronSchedule;
  }

  public Map<String, byte[]> getMemo() {
    return memo;
  }

  public void setMemo(Map<String, byte[]> memo) {
    this.memo = memo;
  }

  public Map<String, byte[]> getSearchAttributes() {
    return searchAttributes;
  }

  public void setSearchAttributes(Map<String, byte[]> searchAttributes) {
    this.searchAttributes = searchAttributes;
  }

  public Map<String, byte[]> getContext() {
    return context;
  }

  public void setContext(Map<String, byte[]> context) {
    this.context = context;
  }

  public StartWorkflowExecutionParameters withRetryParameters(RetryParameters retryParameters) {
    this.retryParameters = retryParameters;
    return this;
  }

  public static StartWorkflowExecutionParameters fromWorkflowOptions(WorkflowOptions options) {
    StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
    parameters.setExecutionStartToCloseTimeoutSeconds(
        getSeconds(options.getExecutionStartToCloseTimeout()));
    parameters.setTaskStartToCloseTimeoutSeconds(getSeconds(options.getTaskStartToCloseTimeout()));
    parameters.setTaskList(options.getTaskList());
    parameters.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      RetryParameters rp = new RetryParameters();
      rp.setBackoffCoefficient(retryOptions.getBackoffCoefficient());
      rp.setExpirationIntervalInSeconds(getSeconds(retryOptions.getExpiration()));
      rp.setInitialIntervalInSeconds(getSeconds(retryOptions.getInitialInterval()));
      rp.setMaximumIntervalInSeconds(getSeconds(retryOptions.getMaximumInterval()));
      rp.setMaximumAttempts(retryOptions.getMaximumAttempts());
      List<String> reasons = new ArrayList<>();
      // Use exception type name as the reason
      List<Class<? extends Throwable>> doNotRetry = retryOptions.getDoNotRetry();
      if (doNotRetry != null) {
        for (Class<? extends Throwable> r : doNotRetry) {
          reasons.add(r.getName());
        }
        rp.setNonRetriableErrorReasons(reasons);
      }
      parameters.setRetryParameters(rp);
    }

    if (options.getCronSchedule() != null) {
      parameters.setCronSchedule(options.getCronSchedule());
    }
    return parameters;
  }

  private static int getSeconds(Duration expiration) {
    if (expiration == null) {
      return 0;
    }
    return (int) expiration.getSeconds();
  }

  @Override
  public String toString() {
    return "StartWorkflowExecutionParameters{"
        + "workflowId='"
        + workflowId
        + '\''
        + ", workflowType="
        + workflowType
        + ", taskList='"
        + taskList
        + '\''
        + ", input="
        + Arrays.toString(input)
        + ", executionStartToCloseTimeoutSeconds="
        + executionStartToCloseTimeoutSeconds
        + ", taskStartToCloseTimeoutSeconds="
        + taskStartToCloseTimeoutSeconds
        + ", workflowIdReusePolicy="
        + workflowIdReusePolicy
        + ", retryParameters="
        + retryParameters
        + ", cronSchedule='"
        + cronSchedule
        + '\''
        + ", memo='"
        + memo
        + '\''
        + ", searchAttributes='"
        + searchAttributes
        + ", context='"
        + context
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StartWorkflowExecutionParameters that = (StartWorkflowExecutionParameters) o;
    return executionStartToCloseTimeoutSeconds == that.executionStartToCloseTimeoutSeconds
        && taskStartToCloseTimeoutSeconds == that.taskStartToCloseTimeoutSeconds
        && Objects.equals(workflowId, that.workflowId)
        && Objects.equals(workflowType, that.workflowType)
        && Objects.equals(taskList, that.taskList)
        && Arrays.equals(input, that.input)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equals(retryParameters, that.retryParameters)
        && Objects.equals(cronSchedule, that.cronSchedule)
        && Objects.equals(memo, that.memo)
        && Objects.equals(searchAttributes, that.searchAttributes)
        && Objects.equals(context, that.context);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            workflowId,
            workflowType,
            taskList,
            executionStartToCloseTimeoutSeconds,
            taskStartToCloseTimeoutSeconds,
            workflowIdReusePolicy,
            retryParameters,
            cronSchedule,
            memo,
            searchAttributes,
            context);
    result = 31 * result + Arrays.hashCode(input);
    return result;
  }
}
