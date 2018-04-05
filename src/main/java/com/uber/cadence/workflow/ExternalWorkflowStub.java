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

package com.uber.cadence.workflow;

import com.uber.cadence.WorkflowExecution;

/**
 * Supports signalling and cancelling any workflows by the workflow type and their id. This is
 * useful when an external workflow type is not known at the compile time and to call workflows in
 * other languages.
 *
 * @see Workflow#newUntypedExternalWorkflowStub(String)
 */
public interface ExternalWorkflowStub {

  WorkflowExecution getExecution();

  void signal(String signalName, Object... args);

  void cancel();
}
