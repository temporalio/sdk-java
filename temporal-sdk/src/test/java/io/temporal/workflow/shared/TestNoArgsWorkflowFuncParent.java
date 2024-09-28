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

  @Override
  public String update(Integer i) {
    throw new UnsupportedOperationException();
  }
}
