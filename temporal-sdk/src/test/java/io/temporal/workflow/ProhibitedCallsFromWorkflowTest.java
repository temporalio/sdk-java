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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.ITestNamedChild;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestNamedChild;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ProhibitedCallsFromWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflow.class, TestNamedChild.class)
          .build();

  private static WorkflowClient workflowClient;

  @Before
  public void setUp() throws Exception {
    workflowClient = testWorkflowRule.getWorkflowClient();
  }

  @Test
  public void testWorkflowClientCallFromWorkflow() {
    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    client.execute();
  }

  public static class TestWorkflow implements NoArgsWorkflow {
    @Override
    public void execute() {
      ITestNamedChild child = Workflow.newChildWorkflowStub(ITestNamedChild.class);
      try {
        WorkflowClient.execute(child::execute, "hello");
        fail("should be unreachable, we expect an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Cannot be called from workflow thread."));
      }
      try {
        WorkflowClient.start(child::execute, "world");
        fail("should be unreachable, we expect an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Cannot be called from workflow thread."));
      }
      try {
        // let's imagine that the workflow code somehow got a WorkflowClient instance (from DI for
        // example).
        // Let's make sure it still can't trigger it's methods
        workflowClient.getOptions();
        fail("should be unreachable, we expect an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Cannot be called from workflow thread."));
      }
    }
  }
}
