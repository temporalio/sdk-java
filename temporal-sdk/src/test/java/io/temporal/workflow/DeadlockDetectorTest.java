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
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Test;

public class DeadlockDetectorTest {

  private static final String taskQueue = "deadlock-test";
  boolean debugMode = System.getenv("TEMPORAL_DEBUG") != null;

  public static class TestDeadlockWorkflow implements NoArgsWorkflow {

    @Override
    public void execute() {
      Async.procedure(() -> Workflow.await(() -> false));
      Workflow.sleep(Duration.ofSeconds(1));
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        throw Workflow.wrap(e);
      }
    }
  }

  @Test
  public void testDeadlockDetector() {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Throwable.class)
            .build(),
        TestDeadlockWorkflow.class);
    env.start();

    WorkflowClient workflowClient = env.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(taskQueue)
            .build();
    NoArgsWorkflow workflow = workflowClient.newWorkflowStub(NoArgsWorkflow.class, options);
    try {
      workflow.execute();
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
}
