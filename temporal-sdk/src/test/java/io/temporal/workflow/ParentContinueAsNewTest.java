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

package io.temporal.workflow;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.ITestChild;
import io.temporal.workflow.shared.TestWorkflows.TestChild;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ParentContinueAsNewTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowContinueAsNew.class, TestChild.class)
          .build();

  /** Reproduction of a bug when a child of continued as new workflow has the same UUID ID. */
  @Test
  public void testParentContinueAsNew() {

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    Assert.assertEquals("foo", client.execute("not empty"));
  }

  public static class TestParentWorkflowContinueAsNew implements TestWorkflow1 {

    private final ITestChild child =
        Workflow.newChildWorkflowStub(
            ITestChild.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    private final TestWorkflow1 self = Workflow.newContinueAsNewStub(TestWorkflow1.class);

    @Override
    public String execute(String arg) {
      child.execute("Hello", 0);
      if (arg.length() > 0) {
        self.execute(""); // continue as new
      }
      return "foo";
    }
  }
}
