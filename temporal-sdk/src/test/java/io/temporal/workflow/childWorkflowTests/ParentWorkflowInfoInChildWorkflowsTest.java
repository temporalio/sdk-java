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

package io.temporal.workflow.childWorkflowTests;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestNoArgsWorkflowFuncParent;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ParentWorkflowInfoInChildWorkflowsTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestMultiArgsWorkflowFuncChild.class, TestNoArgsWorkflowFuncParent.class)
          .build();

  @Test
  public void testParentWorkflowInfoInChildWorkflows() {

    String workflowId = "testParentWorkflowInfoInChildWorkflows";
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestNoArgsWorkflowFunc parent =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestNoArgsWorkflowFunc.class, workflowOptions);

    String result = parent.func();
    String expected = String.format("%s - %s", false, workflowId);
    Assert.assertEquals(expected, result);
  }

  public static class TestMultiArgsWorkflowFuncChild implements Test2ArgWorkflowFunc {
    @Override
    public String func2(String s, int i) {
      WorkflowInfo wi = Workflow.getInfo();
      Optional<String> parentId = wi.getParentWorkflowId();
      return parentId.get();
    }

    @Override
    public String update(Integer i) {
      throw new UnsupportedOperationException();
    }
  }
}
