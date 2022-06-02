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

package io.temporal.workflow;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNoArgsWorkflowFuncParent;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GetAttemptFromWorkflowInfoTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNoArgsWorkflowFuncParent.class, TestAttemptReturningWorkflowFunc.class)
          .build();

  @Test
  public void testGetAttemptFromWorkflowInfo() {
    String workflowId = "testGetAttemptWorkflow";
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestGetAttemptWorkflowsFunc workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestGetAttemptWorkflowsFunc.class, workflowOptions);
    int attempt = workflow.func();
    Assert.assertEquals(1, attempt);
  }

  @WorkflowInterface
  public interface TestGetAttemptWorkflowsFunc {

    @WorkflowMethod
    int func();
  }

  public static class TestAttemptReturningWorkflowFunc implements TestGetAttemptWorkflowsFunc {
    @Override
    public int func() {
      WorkflowInfo wi = Workflow.getInfo();
      return wi.getAttempt();
    }
  }
}
