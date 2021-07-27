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

package io.temporal.workflow.shared;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Optional;

public class TestNoArgsWorkflowFuncParent
    implements TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc {
  @Override
  public String func() {
    ChildWorkflowOptions workflowOptions =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(100))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .build();
    TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc child =
        Workflow.newChildWorkflowStub(
            TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc.class, workflowOptions);

    Optional<String> parentWorkflowId = Workflow.getInfo().getParentWorkflowId();
    String childsParentWorkflowId = child.func2(null, 0);

    String result = String.format("%s - %s", parentWorkflowId.isPresent(), childsParentWorkflowId);
    return result;
  }
}
