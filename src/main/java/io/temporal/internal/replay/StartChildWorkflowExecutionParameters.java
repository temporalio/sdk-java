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

import com.google.common.base.Objects;
import io.temporal.internal.common.RetryParameters;
import io.temporal.proto.common.ParentClosePolicy;
import io.temporal.proto.common.WorkflowIdReusePolicy;
import io.temporal.proto.common.WorkflowType;
import io.temporal.workflow.ChildWorkflowCancellationType;
import java.util.Arrays;
import java.util.Map;

public final class StartChildWorkflowExecutionParameters {

  public static final class Builder {

    private String namespace;

    private long executionStartToCloseTimeoutSeconds;

    private byte[] input;

    private String taskList;

    private long taskStartToCloseTimeoutSeconds;

    private String workflowId;

    private WorkflowType workflowType;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private RetryParameters retryParameters;

    private String cronSchedule;

    private Map<String, byte[]> context;

    private ParentClosePolicy parentClosePolicy;

    private ChildWorkflowCancellationType cancellationType;

    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder setExecutionStartToCloseTimeoutSeconds(
        long executionStartToCloseTimeoutSeconds) {
      this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
      return this;
    }

    public Builder setInput(byte[] input) {
      this.input = input;
      return this;
    }

    public Builder setTaskList(String taskList) {
      this.taskList = taskList;
      return this;
    }

    public Builder setTaskStartToCloseTimeoutSeconds(long taskStartToCloseTimeoutSeconds) {
      this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
      return this;
    }

    public Builder setWorkflowId(String workflowId) {
      this.workflowId = workflowId;
      return this;
    }

    public Builder setWorkflowType(WorkflowType workflowType) {
      this.workflowType = workflowType;
      return this;
    }

    public Builder setWorkflowIdReusePolicy(WorkflowIdReusePolicy workflowIdReusePolicy) {
      this.workflowIdReusePolicy = workflowIdReusePolicy;
      return this;
    }

    public Builder setRetryParameters(RetryParameters retryParameters) {
      this.retryParameters = retryParameters;
      return this;
    }

    public Builder setCronSchedule(String cronSchedule) {
      this.cronSchedule = cronSchedule;
      return this;
    }

    public Builder setContext(Map<String, byte[]> context) {
      this.context = context;
      return this;
    }

    public Builder setParentClosePolicy(ParentClosePolicy parentClosePolicy) {
      this.parentClosePolicy = parentClosePolicy;
      return this;
    }

    public Builder setCancellationType(ChildWorkflowCancellationType cancellationType) {
      this.cancellationType = cancellationType;
      return this;
    }

    public StartChildWorkflowExecutionParameters build() {
      return new StartChildWorkflowExecutionParameters(
          namespace,
          input,
          executionStartToCloseTimeoutSeconds,
          taskList,
          taskStartToCloseTimeoutSeconds,
          workflowId,
          workflowType,
          workflowIdReusePolicy,
          retryParameters,
          cronSchedule,
          context,
          parentClosePolicy,
          cancellationType);
    }
  }

  private final String namespace;

  private final long executionStartToCloseTimeoutSeconds;

  private final byte[] input;

  private final String taskList;

  private final long taskStartToCloseTimeoutSeconds;

  private final String workflowId;

  private final WorkflowType workflowType;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final RetryParameters retryParameters;

  private final String cronSchedule;

  private Map<String, byte[]> context;

  private final ParentClosePolicy parentClosePolicy;

  private final ChildWorkflowCancellationType cancellationType;

  private StartChildWorkflowExecutionParameters(
      String namespace,
      byte[] input,
      long executionStartToCloseTimeoutSeconds,
      String taskList,
      long taskStartToCloseTimeoutSeconds,
      String workflowId,
      WorkflowType workflowType,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      RetryParameters retryParameters,
      String cronSchedule,
      Map<String, byte[]> context,
      ParentClosePolicy parentClosePolicy,
      ChildWorkflowCancellationType cancellationType) {
    this.namespace = namespace;
    this.input = input;
    this.executionStartToCloseTimeoutSeconds = executionStartToCloseTimeoutSeconds;
    this.taskList = taskList;
    this.taskStartToCloseTimeoutSeconds = taskStartToCloseTimeoutSeconds;
    this.workflowId = workflowId;
    this.workflowType = workflowType;
    this.workflowIdReusePolicy = workflowIdReusePolicy;
    this.retryParameters = retryParameters;
    this.cronSchedule = cronSchedule;
    this.context = context;
    this.parentClosePolicy = parentClosePolicy;
    this.cancellationType = cancellationType;
  }

  public String getNamespace() {
    return namespace;
  }

  public long getExecutionStartToCloseTimeoutSeconds() {
    return executionStartToCloseTimeoutSeconds;
  }

  public byte[] getInput() {
    return input;
  }

  public String getTaskList() {
    return taskList;
  }

  public long getTaskStartToCloseTimeoutSeconds() {
    return taskStartToCloseTimeoutSeconds;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public WorkflowType getWorkflowType() {
    return workflowType;
  }

  public WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
    return workflowIdReusePolicy;
  }

  public RetryParameters getRetryParameters() {
    return retryParameters;
  }

  public String getCronSchedule() {
    return cronSchedule;
  }

  public Map<String, byte[]> getContext() {
    return context;
  }

  public ParentClosePolicy getParentClosePolicy() {
    return parentClosePolicy;
  }

  public ChildWorkflowCancellationType getCancellationType() {
    return cancellationType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StartChildWorkflowExecutionParameters that = (StartChildWorkflowExecutionParameters) o;
    return executionStartToCloseTimeoutSeconds == that.executionStartToCloseTimeoutSeconds
        && taskStartToCloseTimeoutSeconds == that.taskStartToCloseTimeoutSeconds
        && cancellationType == that.cancellationType
        && Objects.equal(namespace, that.namespace)
        && Arrays.equals(input, that.input)
        && Objects.equal(taskList, that.taskList)
        && Objects.equal(workflowId, that.workflowId)
        && Objects.equal(workflowType, that.workflowType)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equal(retryParameters, that.retryParameters)
        && Objects.equal(cronSchedule, that.cronSchedule)
        && Objects.equal(context, that.context)
        && parentClosePolicy == that.parentClosePolicy;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        namespace,
        executionStartToCloseTimeoutSeconds,
        Arrays.hashCode(input),
        taskList,
        taskStartToCloseTimeoutSeconds,
        workflowId,
        workflowType,
        workflowIdReusePolicy,
        retryParameters,
        cronSchedule,
        context,
        parentClosePolicy,
        cancellationType);
  }

  @Override
  public String toString() {
    return "StartChildWorkflowExecutionParameters{"
        + "namespace='"
        + namespace
        + '\''
        + ", executionStartToCloseTimeoutSeconds="
        + executionStartToCloseTimeoutSeconds
        + ", input="
        + Arrays.toString(input)
        + ", taskList='"
        + taskList
        + '\''
        + ", taskStartToCloseTimeoutSeconds="
        + taskStartToCloseTimeoutSeconds
        + ", workflowId='"
        + workflowId
        + '\''
        + ", workflowType="
        + workflowType
        + ", workflowIdReusePolicy="
        + workflowIdReusePolicy
        + ", retryParameters="
        + retryParameters
        + ", cronSchedule='"
        + cronSchedule
        + '\''
        + ", context="
        + context
        + ", parentClosePolicy="
        + parentClosePolicy
        + ", cancellationType="
        + cancellationType
        + '}';
  }
}
