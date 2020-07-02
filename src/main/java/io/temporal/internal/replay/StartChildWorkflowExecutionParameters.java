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
import io.temporal.common.v1.Payload;
import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.WorkflowType;
import io.temporal.enums.v1.ParentClosePolicy;
import io.temporal.enums.v1.WorkflowIdReusePolicy;
import io.temporal.internal.common.RetryParameters;
import io.temporal.workflow.ChildWorkflowCancellationType;
import java.util.Map;

public final class StartChildWorkflowExecutionParameters {

  public static final class Builder {

    private String namespace;

    private long workflowRunTimeoutSeconds;

    private long workflowExecutionTimeoutSeconds;

    private Payloads input;

    private String taskQueue;

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

    public Builder setWorkflowExecutionTimeoutSeconds(long workflowExecutionTimeoutSeconds) {
      this.workflowExecutionTimeoutSeconds = workflowExecutionTimeoutSeconds;
      return this;
    }

    public Builder setInput(Payloads input) {
      this.input = input;
      return this;
    }

    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
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
          workflowExecutionTimeoutSeconds,
          taskQueue,
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

  private final long workflowExecutionTimeoutSeconds;

  private final Payloads input;

  private final String taskQueue;

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
      long workflowExecutionTimeoutSeconds,
      String taskQueue,
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
    this.workflowExecutionTimeoutSeconds = workflowExecutionTimeoutSeconds;
    this.taskQueue = taskQueue;
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

  public long getWorkflowExecutionTimeoutSeconds() {
    return workflowExecutionTimeoutSeconds;
  }

  public Payloads getInput() {
    return input;
  }

  public String getTaskQueue() {
    return taskQueue;
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
        && workflowExecutionTimeoutSeconds == that.workflowExecutionTimeoutSeconds
        && workflowTaskTimeoutSeconds == that.workflowTaskTimeoutSeconds
        && Objects.equal(namespace, that.namespace)
        && Objects.equal(input, that.input)
        && Objects.equal(taskQueue, that.taskQueue)
        && Objects.equal(workflowId, that.workflowId)
        && Objects.equal(workflowType, that.workflowType)
        && workflowIdReusePolicy == that.workflowIdReusePolicy
        && Objects.equal(retryParameters, that.retryParameters)
        && Objects.equal(cronSchedule, that.cronSchedule)
        && Objects.equal(context, that.context)
        && parentClosePolicy == that.parentClosePolicy
        && cancellationType == that.cancellationType;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        namespace,
        workflowRunTimeoutSeconds,
        workflowExecutionTimeoutSeconds,
        input,
        taskQueue,
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
        + ", workflowExecutionTimeoutSeconds="
        + workflowExecutionTimeoutSeconds
        + ", input="
        + input
        + ", taskQueue='"
        + taskQueue
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
