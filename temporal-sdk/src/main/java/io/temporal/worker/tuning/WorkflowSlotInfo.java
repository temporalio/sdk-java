/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.worker.tuning;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import java.util.Objects;
import javax.annotation.Nonnull;

public class WorkflowSlotInfo {
  private final String workflowType;
  private final String taskQueue;
  private final String workflowId;
  private final String runId;
  private final String workerIdentity;
  private final String workerBuildId;

  public WorkflowSlotInfo(
      String workflowType,
      String taskQueue,
      String workflowId,
      String runId,
      String workerIdentity,
      String workerBuildId) {
    this.workflowType = workflowType;
    this.taskQueue = taskQueue;
    this.workflowId = workflowId;
    this.runId = runId;
    this.workerIdentity = workerIdentity;
    this.workerBuildId = workerBuildId;
  }

  public WorkflowSlotInfo(
      @Nonnull PollWorkflowTaskQueueResponse response,
      @Nonnull PollWorkflowTaskQueueRequest request) {
    this(
        response.getWorkflowType().getName(),
        request.getTaskQueue().getNormalName(),
        response.getWorkflowExecution().getWorkflowId(),
        response.getWorkflowExecution().getRunId(),
        request.getIdentity(),
        request.getWorkerVersionCapabilities().getBuildId());
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public String getRunId() {
    return runId;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public String getWorkerIdentity() {
    return workerIdentity;
  }

  public String getWorkerBuildId() {
    return workerBuildId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowSlotInfo that = (WorkflowSlotInfo) o;
    return Objects.equals(workflowType, that.workflowType)
        && Objects.equals(taskQueue, that.taskQueue)
        && Objects.equals(workflowId, that.workflowId)
        && Objects.equals(runId, that.runId)
        && Objects.equals(workerIdentity, that.workerIdentity)
        && Objects.equals(workerBuildId, that.workerBuildId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workflowType, taskQueue, workflowId, runId, workerIdentity, workerBuildId);
  }

  @Override
  public String toString() {
    return "WorkflowSlotInfo{"
        + "workflowType='"
        + workflowType
        + '\''
        + ", taskQueue='"
        + taskQueue
        + '\''
        + ", workflowId='"
        + workflowId
        + '\''
        + ", runId='"
        + runId
        + '\''
        + ", workerIdentity='"
        + workerIdentity
        + '\''
        + ", workerBuildId='"
        + workerBuildId
        + '\''
        + '}';
  }
}
