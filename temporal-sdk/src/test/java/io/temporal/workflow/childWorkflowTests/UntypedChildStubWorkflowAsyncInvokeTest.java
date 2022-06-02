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

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UntypedChildStubWorkflowAsyncInvokeTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestUntypedChildStubWorkflowAsyncInvoke.class, TestMultiArgWorkflowImpl.class)
          .build();

  @Test
  public void testUntypedChildStubWorkflowAsyncInvoke() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStub200sTimeoutOptions(TestWorkflow1.class);
    Assert.assertEquals(null, client.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestUntypedChildStubWorkflowAsyncInvoke implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestNoArgsWorkflowFunc", workflowOptions);
      Assert.assertEquals("func", Async.function(stubF::<String>execute, String.class).get());
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      Assert.assertEquals("1", Async.function(stubF1::<String>execute, String.class, "1").get());
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub("Test2ArgWorkflowFunc", workflowOptions);
      Assert.assertEquals(
          "12", Async.function(stubF2::<String>execute, String.class, "1", 2).get());
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub("Test3ArgWorkflowFunc", workflowOptions);
      Assert.assertEquals(
          "123", Async.function(stubF3::<String>execute, String.class, "1", 2, 3).get());
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub("Test4ArgWorkflowFunc", workflowOptions);
      Assert.assertEquals(
          "1234", Async.function(stubF4::<String>execute, String.class, "1", 2, 3, 4).get());
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub("Test5ArgWorkflowFunc", workflowOptions);
      Assert.assertEquals(
          "12345", Async.function(stubF5::<String>execute, String.class, "1", 2, 3, 4, 5).get());

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestNoArgsWorkflowProc", workflowOptions);
      Async.procedure(stubP::<Void>execute, Void.class).get();
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub("Test1ArgWorkflowProc", workflowOptions);
      Async.procedure(stubP1::<Void>execute, Void.class, "1").get();
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub("Test2ArgWorkflowProc", workflowOptions);
      Async.procedure(stubP2::<Void>execute, Void.class, "1", 2).get();
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub("Test3ArgWorkflowProc", workflowOptions);
      Async.procedure(stubP3::<Void>execute, Void.class, "1", 2, 3).get();
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub("Test4ArgWorkflowProc", workflowOptions);
      Async.procedure(stubP4::<Void>execute, Void.class, "1", 2, 3, 4).get();
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub("Test5ArgWorkflowProc", workflowOptions);
      Async.procedure(stubP5::<Void>execute, Void.class, "1", 2, 3, 4, 5).get();
      return null;
    }
  }
}
