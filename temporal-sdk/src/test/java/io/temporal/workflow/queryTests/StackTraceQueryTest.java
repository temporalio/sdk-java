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

package io.temporal.workflow.queryTests;

import static io.temporal.client.WorkflowClient.QUERY_TYPE_STACK_TRACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class StackTraceQueryTest {
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testUntypedStubStackTrace() {
    WorkflowStub workflowStub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    WorkflowExecution execution = workflowStub.start(testWorkflowRule.getTaskQueue());
    testWorkflowRule.sleep(Duration.ofMillis(500));
    String stackTrace = workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class);
    assertTrue(stackTrace, stackTrace.contains("TestWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("sleepActivity"));
    // Test stub created from workflow execution.
    workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, workflowStub.getWorkflowType());
    stackTrace = workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class);
    assertTrue(stackTrace, stackTrace.contains("TestWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("sleepActivity"));

    // wait for a completion
    workflowStub.getResult(String.class);
    // No stacktrace after the workflow is completed. Assert message.
    assertEquals("Workflow is closed.", workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class));
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities.VariousTestActivities activities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Invoke synchronously in a separate thread for testing purposes only.
      // In real workflows use
      // Async.function(activities::sleepActivity, 1000, 10)
      Promise<String> a1 = Async.function(() -> activities.sleepActivity(1000L, 10));
      Workflow.sleep(2000);
      return a1.get();
    }
  }
}
