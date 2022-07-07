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

import io.temporal.api.common.v1.WorkflowExecution;

/**
 * Thrown when a workflow with the given id is not known to the Temporal service or in an incorrect
 * state to perform the operation.
 *
 * <p>Examples of possible causes:
 *
 * <ul>
 *   <li>workflow id doesn't exist
 *   <li>workflow was purged from the service after reaching its retention limit
 *   <li>attempt to signal a workflow that is completed
 * </ul>
 */
public final class WorkflowNotFoundException extends WorkflowException {

  public WorkflowNotFoundException(
      WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
