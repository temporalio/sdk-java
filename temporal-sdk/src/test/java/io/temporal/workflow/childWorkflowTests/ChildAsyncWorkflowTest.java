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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertEquals;

import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.*;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildAsyncWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestChildAsyncWorkflow.class, TestMultiArgWorkflowImpl.class)
          .build();

  @Test
  public void testChildAsyncWorkflow() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStub200sTimeoutOptions(TestWorkflow1.class);
    Assert.assertEquals(null, client.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestChildAsyncWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      TestNoArgsWorkflowFunc stubF =
          Workflow.newChildWorkflowStub(TestNoArgsWorkflowFunc.class, workflowOptions);
      Assert.assertEquals("func", Async.function(stubF::func).get());
      Test1ArgWorkflowFunc stubF1 =
          Workflow.newChildWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
      assertEquals(1, (int) Async.function(stubF1::func1, 1).get());
      Test2ArgWorkflowFunc stubF2 =
          Workflow.newChildWorkflowStub(Test2ArgWorkflowFunc.class, workflowOptions);
      assertEquals("12", Async.function(stubF2::func2, "1", 2).get());
      Test3ArgWorkflowFunc stubF3 =
          Workflow.newChildWorkflowStub(Test3ArgWorkflowFunc.class, workflowOptions);
      assertEquals("123", Async.function(stubF3::func3, "1", 2, 3).get());
      Test4ArgWorkflowFunc stubF4 =
          Workflow.newChildWorkflowStub(Test4ArgWorkflowFunc.class, workflowOptions);
      assertEquals("1234", Async.function(stubF4::func4, "1", 2, 3, 4).get());
      Test5ArgWorkflowFunc stubF5 =
          Workflow.newChildWorkflowStub(Test5ArgWorkflowFunc.class, workflowOptions);
      assertEquals("12345", Async.function(stubF5::func5, "1", 2, 3, 4, 5).get());
      Test6ArgWorkflowFunc stubF6 =
          Workflow.newChildWorkflowStub(Test6ArgWorkflowFunc.class, workflowOptions);
      assertEquals("123456", Async.function(stubF6::func6, "1", 2, 3, 4, 5, 6).get());

      TestNoArgsWorkflowProc stubP =
          Workflow.newChildWorkflowStub(TestNoArgsWorkflowProc.class, workflowOptions);
      Async.procedure(stubP::proc).get();
      Test1ArgWorkflowProc stubP1 =
          Workflow.newChildWorkflowStub(Test1ArgWorkflowProc.class, workflowOptions);
      Async.procedure(stubP1::proc1, "1").get();
      Test2ArgWorkflowProc stubP2 =
          Workflow.newChildWorkflowStub(Test2ArgWorkflowProc.class, workflowOptions);
      Async.procedure(stubP2::proc2, "1", 2).get();
      Test3ArgWorkflowProc stubP3 =
          Workflow.newChildWorkflowStub(Test3ArgWorkflowProc.class, workflowOptions);
      Async.procedure(stubP3::proc3, "1", 2, 3).get();
      Test4ArgWorkflowProc stubP4 =
          Workflow.newChildWorkflowStub(Test4ArgWorkflowProc.class, workflowOptions);
      Async.procedure(stubP4::proc4, "1", 2, 3, 4).get();
      Test5ArgWorkflowProc stubP5 =
          Workflow.newChildWorkflowStub(Test5ArgWorkflowProc.class, workflowOptions);
      Async.procedure(stubP5::proc5, "1", 2, 3, 4, 5).get();
      Test6ArgWorkflowProc stubP6 =
          Workflow.newChildWorkflowStub(Test6ArgWorkflowProc.class, workflowOptions);
      Async.procedure(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();
      return null;
    }
  }
}
