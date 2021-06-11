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

package io.temporal.workflow.signalTests;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalExternalWorkflowImmediateCancellationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalExternalWorkflowImmediateCancellation.class)
          .build();

  @Test
  public void testSignalExternalWorkflowImmediateCancellation() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    try {
      client.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class TestSignalExternalWorkflowImmediateCancellation implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId("invalid id").build();
      TestSignaledWorkflow workflow =
          Workflow.newExternalWorkflowStub(TestSignaledWorkflow.class, parentExecution);
      CompletablePromise<Void> signal = Workflow.newPromise();
      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> signal.completeFrom(Async.procedure(workflow::signal, "World")));
      scope.run();
      scope.cancel();
      try {
        signal.get();
      } catch (IllegalArgumentException e) {
        // expected
      }
      return "result";
    }
  }
}
