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

package io.temporal.internal.common;

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.google.common.base.Objects;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.v1.Payload;
import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.WorkflowType;
import io.temporal.enums.v1.WorkflowIdReusePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StartWorkflowExecutionParameters {

  private String workflowId;

  private WorkflowType workflowType;

  private String taskQueue;

  private Optional<Payloads> input;

  private int workflowRunTimeoutSeconds;

  private int workflowExecutionTimeoutSeconds;

  private int workflowTaskTimeoutSeconds;

  private WorkflowIdReusePolicy workflowIdReusePolicy;

  private RetryParameters retryParameters;

  private String cronSchedule;

  private Map<String, Payload> memo;

  private Map<String, Payload> searchAttributes;

  private Map<String, Payload> context;

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
   * Returns the value of the TaskQueue property for this object.
   *
   * @return The value of the TaskQueue property for this object.
   */
  public String getTaskQueue() {
    return taskQueue;
  }

  /**
   * Sets the value of the TaskQueue property for this object.
   *
   * @param taskQueue The new value for the TaskQueue property for this object.
   */
  public void setTaskQueue(String taskQueue) {
    this.taskQueue = taskQueue;
  }

  /**
   * Sets the value of the TaskQueue property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * @param taskQueue The new value for the TaskQueue property for this object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public StartWorkflowExecutionParameters withTaskQueue(String taskQueue) {
    this.taskQueue = taskQueue;
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
  public Optional<Payloads> getInput() {
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
  public void setInput(Optional<Payloads> input) {
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
  public StartWorkflowExecutionParameters withInput(Optional<Payloads> input) {
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
  public int getWorkflowRunTimeoutSeconds() {
    return workflowRunTimeoutSeconds;
  }

  /**
   * Sets the value of the StartToCloseTimeout property for this object.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 64<br>
   *
   * @param workflowRunTimeoutSeconds The new value for the StartToCloseTimeout property for this
   *     object.
   */
  public void setWorkflowRunTimeoutSeconds(int workflowRunTimeoutSeconds) {
    this.workflowRunTimeoutSeconds = workflowRunTimeoutSeconds;
  }

  /**
   * Sets the value of the StartToCloseTimeout property for this object.
   *
   * <p>Returns a reference to this object so that method calls can be chained together.
   *
   * <p><b>Constraints:</b><br>
   * <b>Length: </b>0 - 64<br>
   *
   * @param workflowRunTimeoutSeconds The new value for the StartToCloseTimeout property for this
   *     object.
   * @return A reference to this updated object so that method calls can be chained together.
   */
  public StartWorkflowExecutionParameters withWorkflowRunTimeoutSeconds(
      int workflowRunTimeoutSeconds) {
    this.workflowRunTimeoutSeconds = workflowRunTimeoutSeconds;
    return this;
  }

  public int getWorkflowExecutionTimeoutSeconds() {
    return workflowExecutionTimeoutSeconds;
  }

  public void setWorkflowExecutionTimeoutSeconds(int workflowExecutionTimeoutSeconds) {
    this.workflowExecutionTimeoutSeconds = workflowExecutionTimeoutSeconds;
  }

  public StartWorkflowExecutionParameters withWorkflowExecutionTimeoutSeconds(
      int workflowExecutionTimeoutSeconds) {
    this.workflowExecutionTimeoutSeconds = workflowExecutionTimeoutSeconds;
    return this;
  }

  public int getWorkflowTaskTimeoutSeconds() {
    return workflowTaskTimeoutSeconds;
  }

  public void setWorkflowTaskTimeoutSeconds(int workflowTaskTimeoutSeconds) {
    this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
  }

  public StartWorkflowExecutionParameters withWorkflowTaskTimeoutSeconds(
      int workflowTaskTimeoutSeconds) {
    this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
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

  public Map<String, Payload> getMemo() {
    return memo;
  }

  public void setMemo(Map<String, Payload> memo) {
    this.memo = memo;
  }

  public Map<String, Payload> getSearchAttributes() {
    return searchAttributes;
  }

  public void setSearchAttributes(Map<String, Payload> searchAttributes) {
    this.searchAttributes = searchAttributes;
  }

  public Map<String, Payload> getContext() {
    return context;
  }

  public void setContext(Map<String, Payload> context) {
    this.context = context;
  }

  public StartWorkflowExecutionParameters withRetryParameters(RetryParameters retryParameters) {
    this.retryParameters = retryParameters;
    return this;
  }

  public static StartWorkflowExecutionParameters fromWorkflowOptions(WorkflowOptions options) {
    StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
    parameters.setWorkflowRunTimeoutSeconds(roundUpToSeconds(options.getWorkflowRunTimeout()));
    parameters.setWorkflowExecutionTimeoutSeconds(
        roundUpToSeconds(options.getWorkflowExecutionTimeout()));
    parameters.setWorkflowTaskTimeoutSeconds(roundUpToSeconds(options.getWorkflowTaskTimeout()));
    parameters.setTaskQueue(options.getTaskQueue());
    parameters.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      RetryParameters rp = new RetryParameters();
      rp.setBackoffCoefficient(retryOptions.getBackoffCoefficient());
      rp.setInitialIntervalInSeconds(roundUpToSeconds(retryOptions.getInitialInterval()));
      rp.setMaximumIntervalInSeconds(roundUpToSeconds(retryOptions.getMaximumInterval()));
      rp.setMaximumAttempts(retryOptions.getMaximumAttempts());
      List<String> types = new ArrayList<>();
      // Use exception type name as the reason
      String[] doNotRetry = retryOptions.getDoNotRetry();
      if (doNotRetry != null) {
        for (String r : doNotRetry) {
          types.add(r);
        }
        rp.setNonRetriableErrorTypes(types);
      }
      parameters.setRetryParameters(rp);
    }

    if (options.getCronSchedule() != null) {
      parameters.setCronSchedule(options.getCronSchedule());
    }
    return parameters;
  }

  @Override
  public String toString() {
    return "StartWorkflowExecutionParameters{"
        + "workflowId='"
        + workflowId
        + '\''
        + ", workflowType="
        + workflowType
        + ", taskQueue='"
        + taskQueue
        + '\''
        + ", input="
        + input
        + ", workflowRunTimeoutSeconds="
        + workflowRunTimeoutSeconds
        + ", workflowTaskTimeoutSeconds="
        + workflowTaskTimeoutSeconds
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
    return workflowRunTimeoutSeconds == that.workflowRunTimeoutSeconds
        && workflowTaskTimeoutSeconds == that.workflowTaskTimeoutSeconds
        && Objects.equal(workflowId, that.workflowId)
        && Objects.equal(workflowType, that.workflowType)
        && Objects.equal(taskQueue, that.taskQueue)
        && Objects.equal(input, that.input)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equal(retryParameters, that.retryParameters)
        && Objects.equal(cronSchedule, that.cronSchedule)
        && Objects.equal(memo, that.memo)
        && Objects.equal(searchAttributes, that.searchAttributes)
        && Objects.equal(context, that.context);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        workflowId,
        workflowType,
        taskQueue,
        input,
        workflowRunTimeoutSeconds,
        workflowTaskTimeoutSeconds,
        workflowIdReusePolicy,
        retryParameters,
        cronSchedule,
        memo,
        searchAttributes,
        context);
  }
}
