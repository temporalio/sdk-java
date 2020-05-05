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
import io.temporal.proto.common.Payload;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.WorkflowIdReusePolicy;
import io.temporal.proto.common.WorkflowType;
import io.temporal.workflow.ChildWorkflowCancellationType;
import java.util.Map;

public final class StartChildWorkflowExecutionParameters {

  public static final class Builder {

    private String namespace;

    private long workflowRunTimeoutSeconds;

    private Payloads input;

    private String taskList;

    private long workflowTaskTimeoutSeconds;

    private String workflowId;

    private WorkflowType workflowType;

    private WorkflowIdReusePolicy workflowIdReusePolicy;

    private RetryParameters retryParameters;

    private String cronSchedule;

    private Map<String, Payload> context;

    private ParentClosePolicy parentClosePolicy;

    private ChildWorkflowCancellationType cancellationType;

    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder setWorkflowRunTimeoutSeconds(long workflowRunTimeoutSeconds) {
      this.workflowRunTimeoutSeconds = workflowRunTimeoutSeconds;
      return this;
    }

    public Builder setInput(Payloads input) {
      this.input = input;
      return this;
    }

    public Builder setTaskList(String taskList) {
      this.taskList = taskList;
      return this;
    }

    public Builder setWorkflowTaskTimeoutSeconds(long workflowTaskTimeoutSeconds) {
      this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
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

    public Builder setContext(Map<String, Payload> context) {
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
          workflowRunTimeoutSeconds,
          taskList,
          workflowTaskTimeoutSeconds,
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

  private final long workflowRunTimeoutSeconds;

  private final Payloads input;

  private final String taskList;

  private final long workflowTaskTimeoutSeconds;

  private final String workflowId;

  private final WorkflowType workflowType;

  private final WorkflowIdReusePolicy workflowIdReusePolicy;

  private final RetryParameters retryParameters;

  private final String cronSchedule;

  private Map<String, Payload> context;

  private final ParentClosePolicy parentClosePolicy;

  private final ChildWorkflowCancellationType cancellationType;

  private StartChildWorkflowExecutionParameters(
      String namespace,
      Payloads input,
      long workflowRunTimeoutSeconds,
      String taskList,
      long workflowTaskTimeoutSeconds,
      String workflowId,
      WorkflowType workflowType,
      WorkflowIdReusePolicy workflowIdReusePolicy,
      RetryParameters retryParameters,
      String cronSchedule,
      Map<String, Payload> context,
      ParentClosePolicy parentClosePolicy,
      ChildWorkflowCancellationType cancellationType) {
    this.namespace = namespace;
    this.input = input;
    this.workflowRunTimeoutSeconds = workflowRunTimeoutSeconds;
    this.taskList = taskList;
    this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
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

  public long getWorkflowRunTimeoutSeconds() {
    return workflowRunTimeoutSeconds;
  }

  public Payloads getInput() {
    return input;
  }

  public String getTaskList() {
    return taskList;
  }

  public long getWorkflowTaskTimeoutSeconds() {
    return workflowTaskTimeoutSeconds;
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

  public Map<String, Payload> getContext() {
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
    return workflowRunTimeoutSeconds == that.workflowRunTimeoutSeconds
        && workflowTaskTimeoutSeconds == that.workflowTaskTimeoutSeconds
        && cancellationType == that.cancellationType
        && Objects.equal(namespace, that.namespace)
        && Objects.equal(input, that.input)
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
        workflowRunTimeoutSeconds,
        input,
        taskList,
        workflowTaskTimeoutSeconds,
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
        + ", workflowRunTimeoutSeconds="
        + workflowRunTimeoutSeconds
        + ", input="
        + input
        + ", taskList='"
        + taskList
        + '\''
        + ", workflowTaskTimeoutSeconds="
        + workflowTaskTimeoutSeconds
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
