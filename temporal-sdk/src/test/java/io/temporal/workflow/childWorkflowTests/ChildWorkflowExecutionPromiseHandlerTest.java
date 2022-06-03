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

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.ITestNamedChild;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowExecutionPromiseHandlerTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNamedChild.class, TestChildWorkflowExecutionPromiseHandler.class)
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
    TestWorkflow1 client = workflowStub.newWorkflowStub(TestWorkflow1.class, options);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    assertEquals("FOO", result);
  }

  public static class TestNamedChild implements ITestNamedChild {

    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  public static class TestChildWorkflowExecutionPromiseHandler implements TestWorkflow1 {

    private ITestNamedChild child;

    @Override
    public String execute(String taskQueue) {
      child = Workflow.newChildWorkflowStub(ITestNamedChild.class);
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
