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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowLongArg;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class DeadlockDetectorTest {

  private boolean debugMode = System.getenv("TEMPORAL_DEBUG") != null;
  private WorkflowImplementationOptions workflowImplementationOptions =
      WorkflowImplementationOptions.newBuilder()
          .setFailWorkflowExceptionTypes(Throwable.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(workflowImplementationOptions, TestDeadlockWorkflow.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithDDDTimeout =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(workflowImplementationOptions, TestDeadlockWorkflow.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(500).build())
          .build();

  @Test
  public void testDefaultDeadlockDetector() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    TestWorkflowLongArg workflow =
        workflowClient.newWorkflowStub(
            TestWorkflowLongArg.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(1000))
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .build());
    try {
      workflow.execute(2000);
      if (!debugMode) {
        fail("not reachable in non-debug mode");
      }
    } catch (WorkflowFailedException e) {
      if (debugMode) {
        fail("not reachable in debug mode");
      }
      Throwable failure = e;
      while (failure.getCause() != null) {
        failure = failure.getCause();
      }
      assertTrue(failure.getMessage().contains("Potential deadlock detected"));
      assertTrue(failure.getMessage().contains("Workflow.await"));
    }
  }

  @Test
  public void testSetDeadlockDetector() {
    WorkflowClient workflowClient = testWorkflowRuleWithDDDTimeout.getWorkflowClient();
    TestWorkflowLongArg workflow =
        workflowClient.newWorkflowStub(
            TestWorkflowLongArg.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(1000))
                .setTaskQueue(testWorkflowRuleWithDDDTimeout.getTaskQueue())
                .build());
    try {
      workflow.execute(750);
      if (!debugMode) {
        fail("not reachable in non-debug mode");
      }
    } catch (WorkflowFailedException e) {
      if (debugMode) {
        fail("not reachable in debug mode");
      }
      Throwable failure = e;
      while (failure.getCause() != null) {
        failure = failure.getCause();
      }
      assertTrue(failure.getMessage().contains("Potential deadlock detected"));
      assertTrue(failure.getMessage().contains("Workflow.await"));
    }
  }

  public static class TestDeadlockWorkflow implements TestWorkflowLongArg {

    @Override
    public void execute(long millis) {
      Async.procedure(() -> Workflow.await(() -> false));
      Workflow.sleep(Duration.ofSeconds(1));
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        throw Workflow.wrap(e);
      }
    }
  }
}
