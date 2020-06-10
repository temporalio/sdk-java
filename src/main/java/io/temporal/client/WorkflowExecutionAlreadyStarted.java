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

package io.temporal.client;

import io.temporal.proto.common.WorkflowExecution;

/**
 * This exception is thrown in the following cases:
 * <li>
 *
 *     <ul>
 *       Workflow with the same WorkflowId is currently running.
 * </ul>
 *
 * <ul>
 *   There is a closed workflow with the same ID and the {@link
 *   WorkflowOptions#getWorkflowIdReusePolicy()} is {@link
 *   io.temporal.proto.common.WorkflowIdReusePolicy#RejectDuplicate}.
 * </ul>
 *
 * <ul>
 *   There is successfully closed workflow with the same ID and the {@link
 *   WorkflowOptions#getWorkflowIdReusePolicy()} is {@link
 *   io.temporal.proto.common.WorkflowIdReusePolicy#AllowDuplicateFailedOnly}.
 * </ul>
 *
 * <ul>
 *   Method annotated with {@link io.temporal.workflow.WorkflowMethod} is called <i>more than
 *   once</i> on a stub created through {@link
 *   io.temporal.workflow.Workflow#newChildWorkflowStub(Class)} and the {@link
 *   WorkflowOptions#getWorkflowIdReusePolicy()} is {@link
 *   io.temporal.proto.common.WorkflowIdReusePolicy#AllowDuplicate}
 * </ul>
 */
public final class WorkflowExecutionAlreadyStarted extends WorkflowException {
  public WorkflowExecutionAlreadyStarted(
      WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
