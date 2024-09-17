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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowInitTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestInitWorkflow.class).build();

  @Test
  public void testInit() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals(testWorkflowRule.getTaskQueue(), result);
  }

  @Test
  public void testInitThrowApplicationFailure() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException failure =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(failure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) failure.getCause();
    assertEquals("Empty taskQueue", applicationFailure.getOriginalMessage());
  }

  public static class TestInitWorkflow implements TestWorkflow1 {
    private final String taskQueue;

    @WorkflowInit
    public TestInitWorkflow(String taskQueue) {
      if (taskQueue.isEmpty()) {
        throw ApplicationFailure.newFailure("Empty taskQueue", "TestFailure");
      }
      this.taskQueue = taskQueue;
    }

    @Override
    public String execute(String taskQueue) {
      if (!taskQueue.equals(this.taskQueue)) {
        throw new IllegalArgumentException("Unexpected taskQueue: " + taskQueue);
      }
      return taskQueue;
    }
  }
}
