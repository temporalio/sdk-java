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

package io.temporal.worker.slotsupplier;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;

public class WorkflowSlotInfo {
  private final String workflowType;

  public WorkflowSlotInfo(String workflowType) {
    this.workflowType = workflowType;
  }

  public WorkflowSlotInfo(PollWorkflowTaskQueueResponse response) {
    this(response.getWorkflowType().getName());
  }

  public String getWorkflowType() {
    return workflowType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    WorkflowSlotInfo that = (WorkflowSlotInfo) o;

    return workflowType.equals(that.workflowType);
  }

  @Override
  public int hashCode() {
    return workflowType.hashCode();
  }

  @Override
  public String toString() {
    return "WorkflowSlotInfo{" + "workflowType='" + workflowType + "'}";
  }
}
