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

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.workflow.ChildWorkflowCancellationType;
import java.util.Optional;
import java.util.function.Consumer;

class OpenChildWorkflowRequestInfo extends OpenRequestInfo<Optional<Payloads>, String> {

  private final ChildWorkflowCancellationType cancellationType;
  private final Consumer<WorkflowExecution> executionCallback;

  public OpenChildWorkflowRequestInfo(
      ChildWorkflowCancellationType cancellationType,
      Consumer<WorkflowExecution> executionCallback) {
    this.cancellationType = cancellationType;
    this.executionCallback = executionCallback;
  }

  public ChildWorkflowCancellationType getCancellationType() {
    return cancellationType;
  }

  public Consumer<WorkflowExecution> getExecutionCallback() {
    return executionCallback;
  }
}
