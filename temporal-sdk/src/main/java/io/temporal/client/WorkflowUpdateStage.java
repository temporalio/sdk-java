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

package io.temporal.client;

import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;

public enum WorkflowUpdateStage {
  /**
   * Update request waits for the update to be until the update request has been admitted by the
   * server - it may be the case that due to a considerations like load or resource limits that an
   * update is made to wait before the server will indicate that it has been received and will be
   * processed. This value does not wait for any sort of acknowledgement from a worker.
   */
  ADMITTED(
      UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED),

  /**
   * Update request waits for the update to be accepted (and validated, if there is a validator) by
   * the workflow
   */
  ACCEPTED(
      UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED),

  /** Update request waits for the update to be completed. */
  COMPLETED(
      UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED);

  private final UpdateWorkflowExecutionLifecycleStage policy;

  WorkflowUpdateStage(UpdateWorkflowExecutionLifecycleStage policy) {
    this.policy = policy;
  }

  public UpdateWorkflowExecutionLifecycleStage getProto() {
    return policy;
  }
}
