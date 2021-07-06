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
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildAsyncWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestChildAsyncWorkflow.class,
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class)
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
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
      Assert.assertEquals("func", Async.function(stubF::func).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
      assertEquals(1, (int) Async.function(stubF1::func1, 1).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class, workflowOptions);
      assertEquals("12", Async.function(stubF2::func2, "1", 2).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3 stubF3 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3.class, workflowOptions);
      assertEquals("123", Async.function(stubF3::func3, "1", 2, 3).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4 stubF4 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4.class, workflowOptions);
      assertEquals("1234", Async.function(stubF4::func4, "1", 2, 3, 4).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5 stubF5 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5.class, workflowOptions);
      assertEquals("12345", Async.function(stubF5::func5, "1", 2, 3, 4, 5).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6 stubF6 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6.class, workflowOptions);
      assertEquals("123456", Async.function(stubF6::func6, "1", 2, 3, 4, 5, 6).get());

      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc stubP =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc.class, workflowOptions);
      Async.procedure(stubP::proc).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1 stubP1 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1.class, workflowOptions);
      Async.procedure(stubP1::proc1, "1").get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2 stubP2 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2.class, workflowOptions);
      Async.procedure(stubP2::proc2, "1", 2).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3 stubP3 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3.class, workflowOptions);
      Async.procedure(stubP3::proc3, "1", 2, 3).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4 stubP4 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4.class, workflowOptions);
      Async.procedure(stubP4::proc4, "1", 2, 3, 4).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5 stubP5 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5.class, workflowOptions);
      Async.procedure(stubP5::proc5, "1", 2, 3, 4, 5).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6 stubP6 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6.class, workflowOptions);
      Async.procedure(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();
      return null;
    }
  }
}
