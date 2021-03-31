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

import io.temporal.workflow.*;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestChild;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowTest {

  private static String child2Id;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflow.class, TestNamedChild.class, TestChild.class)
          .build();

  @Test
  public void testChildWorkflow() {
    child2Id = UUID.randomUUID().toString();
    TestWorkflows.TestWorkflow1 client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    assertEquals("HELLO WORLD!", client.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestNamedChild implements TestWorkflows.ITestNamedChild {
    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  public static class TestParentWorkflow implements TestWorkflows.TestWorkflow1 {

    private final TestWorkflows.ITestChild child1 =
        Workflow.newChildWorkflowStub(TestWorkflows.ITestChild.class);
    private final TestWorkflows.ITestNamedChild child2;

    public TestParentWorkflow() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowId(child2Id).build();
      child2 = Workflow.newChildWorkflowStub(TestWorkflows.ITestNamedChild.class, options);
    }

    @Override
    public String execute(String taskQueue) {
      Promise<String> r1 = Async.function(child1::execute, "Hello ", 0);
      String r2 = child2.execute("World!");
      assertEquals(child2Id, Workflow.getWorkflowExecution(child2).get().getWorkflowId());
      return r1.get() + r2;
    }
  }
}
