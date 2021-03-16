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

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowExecutionPromiseHandlerTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNamedChild.class, TestChildWorkflowExecutionPromiseHandler.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  /** Tests that handler of the WorkflowExecution promise is executed in a workflow thread. */
  @Test
  public void testChildWorkflowExecutionPromiseHandler() {
    WorkflowClient workflowStub = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowStub.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    assertEquals("FOO", result);
  }

  public static class TestNamedChild implements WorkflowTest.ITestNamedChild {

    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  public static class TestChildWorkflowExecutionPromiseHandler
      implements TestWorkflows.TestWorkflow1 {

    private WorkflowTest.ITestNamedChild child;

    @Override
    public String execute(String taskQueue) {
      child = Workflow.newChildWorkflowStub(WorkflowTest.ITestNamedChild.class);
      Promise<String> childResult = Async.function(child::execute, "foo");
      Promise<WorkflowExecution> executionPromise = Workflow.getWorkflowExecution(child);
      CompletablePromise<String> result = Workflow.newPromise();
      // Ensure that the callback can execute Workflow.* functions.
      executionPromise.thenApply(
          (we) -> {
            Workflow.sleep(Duration.ofSeconds(1));
            result.complete(childResult.get());
            return null;
          });
      return result.get();
    }
  }
}
