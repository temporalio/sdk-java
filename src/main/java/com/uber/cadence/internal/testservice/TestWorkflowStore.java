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

package com.uber.cadence.internal.testservice;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecutionInfo;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

interface TestWorkflowStore {

  enum WorkflowState {
    OPEN,
    CLOSED
  }

  class TaskListId {

    private final String domain;
    private final String taskListName;

    public TaskListId(String domain, String taskListName) {
      this.domain = Objects.requireNonNull(domain);
      this.taskListName = Objects.requireNonNull(taskListName);
    }

    public String getDomain() {
      return domain;
    }

    public String getTaskListName() {
      return taskListName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || !(o instanceof TaskListId)) {
        return false;
      }

      TaskListId that = (TaskListId) o;

      if (!domain.equals(that.domain)) {
        return false;
      }
      return taskListName.equals(that.taskListName);
    }

    @Override
    public int hashCode() {
      int result = domain.hashCode();
      result = 31 * result + taskListName.hashCode();
      return result;
    }

    @Override
    public String toString() {
      return "TaskListId{"
          + "domain='"
          + domain
          + '\''
          + ", taskListName='"
          + taskListName
          + '\''
          + '}';
    }
  }

  class DecisionTask {

    private final TaskListId taskListId;
    private final PollForDecisionTaskResponse task;

    public DecisionTask(TaskListId taskListId, PollForDecisionTaskResponse task) {
      this.taskListId = taskListId;
      this.task = task;
    }

    public TaskListId getTaskListId() {
      return taskListId;
    }

    public PollForDecisionTaskResponse getTask() {
      return task;
    }
  }

  class ActivityTask {

    private final TaskListId taskListId;
    private final PollForActivityTaskResponse task;

    public ActivityTask(TaskListId taskListId, PollForActivityTaskResponse task) {
      this.taskListId = taskListId;
      this.task = task;
    }

    public TaskListId getTaskListId() {
      return taskListId;
    }

    public PollForActivityTaskResponse getTask() {
      return task;
    }
  }

  SelfAdvancingTimer getTimer();

  long currentTimeMillis();

  long save(RequestContext requestContext)
      throws InternalServiceError, EntityNotExistsError, BadRequestError;

  void applyTimersAndLocks(RequestContext ctx);

  void registerDelayedCallback(Duration delay, Runnable r);

  PollForDecisionTaskResponse pollForDecisionTask(PollForDecisionTaskRequest pollRequest)
      throws InterruptedException;

  PollForActivityTaskResponse pollForActivityTask(PollForActivityTaskRequest pollRequest)
      throws InterruptedException;

  /** @return queryId */
  void sendQueryTask(ExecutionId executionId, TaskListId taskList, PollForDecisionTaskResponse task)
      throws EntityNotExistsError;

  GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      ExecutionId executionId, GetWorkflowExecutionHistoryRequest getRequest)
      throws EntityNotExistsError;

  void getDiagnostics(StringBuilder result);

  List<WorkflowExecutionInfo> listWorkflows(WorkflowState state, Optional<String> workflowId);

  void close();
}
