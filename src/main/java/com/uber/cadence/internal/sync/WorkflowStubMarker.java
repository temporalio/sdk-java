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

package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.workflow.Promise;

/**
 * Interface that stub created through {@link
 * com.uber.cadence.workflow.Workflow#newChildWorkflowStub(Class)} implements. Do not implement or
 * use this interface in any application code. Use {@link
 * com.uber.cadence.workflow.Workflow#getWorkflowExecution(Object)} to access {@link
 * WorkflowExecution} out of a workflow stub.
 */
public interface WorkflowStubMarker {
  String GET_EXECUTION_METHOD_NAME = "__getWorkflowExecution";

  Promise<WorkflowExecution> __getWorkflowExecution();
}
