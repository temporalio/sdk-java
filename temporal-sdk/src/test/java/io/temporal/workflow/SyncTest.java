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

import static io.temporal.client.WorkflowClient.QUERY_TYPE_STACK_TRACE;
import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SyncTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSyncWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testSync() {
    activitiesImpl.setCompletionClient(
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient());
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("activity10", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "newThread null",
            "sleep PT2S",
            "executeActivity ActivityWithDelay",
            "activity ActivityWithDelay",
            "executeActivity Activity2",
            "activity Activity2");
  }

  @Test
  public void testSyncUntypedAndStackTrace() {
    activitiesImpl.setCompletionClient(
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient());
    WorkflowStub workflowStub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    WorkflowExecution execution = workflowStub.start(testWorkflowRule.getTaskQueue());
    testWorkflowRule.sleep(Duration.ofMillis(500));
    String stackTrace = workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class);
    assertTrue(stackTrace, stackTrace.contains("TestSyncWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
    // Test stub created from workflow execution.
    workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, workflowStub.getWorkflowType());
    stackTrace = workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class);
    assertTrue(stackTrace, stackTrace.contains("TestSyncWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
    String result = workflowStub.getResult(String.class);
    assertEquals("activity10", result);
    // No stacktrace after the workflow is closed. Assert message.
    assertEquals("Workflow is closed.", workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class));
  }

  @Test
  public void testWorkflowCancellation() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    client.start(testWorkflowRule.getTaskQueue());
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  @Test
  public void testWorkflowTermination() throws InterruptedException {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    client.start(testWorkflowRule.getTaskQueue());
    Thread.sleep(1000);
    client.terminate("boo", "detail1", "detail2");
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException ignored) {
      assertTrue(ignored.getCause() instanceof TerminatedFailure);
      assertEquals("boo", ((TerminatedFailure) ignored.getCause()).getOriginalMessage());
    }
  }

  public static class TestSyncWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      // Invoke synchronously in a separate thread for testing purposes only.
      // In real workflows use
      // Async.procedure(activities::activityWithDelay, 1000, true)
      Promise<String> a1 = Async.function(() -> activities.activityWithDelay(1000, true));
      Workflow.sleep(2000);
      return activities.activity2(a1.get(), 10);
    }
  }
}
