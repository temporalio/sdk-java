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

package io.temporal.internal.testservice;

import com.google.protobuf.Timestamp;
import io.grpc.Deadline;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

interface TestWorkflowStore {

  enum WorkflowState {
    OPEN,
    CLOSED
  }

  class TaskQueueId {

    private final String namespace;
    private final String taskQueueName;

    public TaskQueueId(String namespace, String taskQueueName) {
      this.namespace = Objects.requireNonNull(namespace);
      this.taskQueueName = Objects.requireNonNull(taskQueueName);
    }

    public String getNamespace() {
      return namespace;
    }

    public String getTaskQueueName() {
      return taskQueueName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || !(o instanceof TaskQueueId)) {
        return false;
      }

      TaskQueueId that = (TaskQueueId) o;

      if (!namespace.equals(that.namespace)) {
        return false;
      }
      return taskQueueName.equals(that.taskQueueName);
    }

    @Override
    public int hashCode() {
      int result = namespace.hashCode();
      result = 31 * result + taskQueueName.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "TaskQueueId{"
          + "namespace='"
          + namespace
          + '\''
          + ", taskQueueName='"
          + taskQueueName
          + '\''
          + '}';
    }
  }

  class WorkflowTask {

    private final TaskQueueId taskQueueId;
    private final PollWorkflowTaskQueueResponse.Builder task;

    public WorkflowTask(TaskQueueId taskQueueId, PollWorkflowTaskQueueResponse.Builder task) {
      this.taskQueueId = taskQueueId;
      this.task = task;
    }

    public TaskQueueId getTaskQueueId() {
      return taskQueueId;
    }

    public PollWorkflowTaskQueueResponse.Builder getTask() {
      return task;
    }
  }

  class ActivityTask {

    private final TaskQueueId taskQueueId;
    private final PollActivityTaskQueueResponse.Builder task;

    public ActivityTask(TaskQueueId taskQueueId, PollActivityTaskQueueResponse.Builder task) {
      this.taskQueueId = taskQueueId;
      this.task = task;
    }

    public TaskQueueId getTaskQueueId() {
      return taskQueueId;
    }

    public PollActivityTaskQueueResponse.Builder getTask() {
      return task;
    }
  }

  SelfAdvancingTimer getTimer();

  Timestamp currentTime();

  long save(RequestContext requestContext);

  void applyTimersAndLocks(RequestContext ctx);

  void registerDelayedCallback(Duration delay, Runnable r);

  /** @return empty if deadline exprired */
  Optional<PollWorkflowTaskQueueResponse.Builder> pollWorkflowTaskQueue(
      PollWorkflowTaskQueueRequest pollRequest, Deadline deadline);

  /** @return empty if deadline exprired */
  Optional<PollActivityTaskQueueResponse.Builder> pollActivityTaskQueue(
      PollActivityTaskQueueRequest pollRequest, Deadline deadline);

  /** @return queryId */
  void sendQueryTask(
      ExecutionId executionId, TaskQueueId taskQueue, PollWorkflowTaskQueueResponse.Builder task);

  GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      ExecutionId executionId, GetWorkflowExecutionHistoryRequest getRequest, Deadline deadline);

  void getDiagnostics(StringBuilder result);

  List<WorkflowExecutionInfo> listWorkflows(WorkflowState state, Optional<String> workflowId);

  void close();
}
