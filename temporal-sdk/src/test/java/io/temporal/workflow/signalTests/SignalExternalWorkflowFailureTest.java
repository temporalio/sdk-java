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

import static io.temporal.api.enums.v1.SignalExternalWorkflowExecutionFailedCause.SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND;
import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.Rule;
import org.junit.Test;

public class SignalExternalWorkflowFailureTest {

  private static String WORKFLOW_ID = "testWorkflowID";
  private static String TEST_SIGNAL = "test";
  private static CountDownLatch latch = new CountDownLatch(1);
  private static CountDownLatch latch2 = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestSignalExternalWorkflowFailure.class,
              WorkflowWithSignalImpl.class,
              SignalingWorkflowImpl.class)
          .build();

  @Test
  public void testSignalExternalWorkflowFailure() {
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
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals(
          "SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND",
          ((ApplicationFailure) e.getCause()).getType());
      assertTrue(e.getCause().getMessage().contains("invalid id"));
    }
  }

  @Test
  public void testTerminateWorkflowSignalError() throws InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId(WORKFLOW_ID)
            .build();
    TestSignaledWorkflow terminatedWorkflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowClient.start(terminatedWorkflow::execute);

    TestWorkflowReturnString signalingWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnString.class);
    WorkflowClient.start(signalingWorkflow::execute);

    // Wait for terminatedWorkflow to start
    latch.await();

    WorkflowStub stub = WorkflowStub.fromTyped(terminatedWorkflow);
    stub.terminate("Mock terminating workflow");

    // Wait for signalingWorkflow to start and terminatedWorkflow to terminate
    latch2.countDown();

    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(signalingWorkflow);
    assertEquals(workflowStub2.getResult(String.class), "Success!");
  }

  public static class TestSignalExternalWorkflowFailure implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId("invalid id").build();
      TestSignaledWorkflow workflow =
          Workflow.newExternalWorkflowStub(TestSignaledWorkflow.class, parentExecution);
      workflow.signal("World");
      return "ignored";
    }
  }

  public static class WorkflowWithSignalImpl implements TestSignaledWorkflow {
    private String signal;

    @Override
    public String execute() {
      latch.countDown();
      Workflow.await(Duration.ofDays(100500), () -> TEST_SIGNAL.equals(signal));
      return signal;
    }

    @Override
    public void signal(String signal) {
      this.signal = signal;
    }
  }

  public static class SignalingWorkflowImpl implements TestWorkflowReturnString {
    @Override
    public String execute() {
      try {
        latch2.await();
        TestSignaledWorkflow stub =
            Workflow.newExternalWorkflowStub(TestSignaledWorkflow.class, WORKFLOW_ID);
        // Signaling a terminated workflow should not succeed
        stub.signal(TEST_SIGNAL);
        fail();
      } catch (Exception e) {
        if (e instanceof ApplicationFailure) {
          // Expected
          ApplicationFailure failure = (ApplicationFailure) e;
          assertEquals(
              failure.getType(),
              SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND
                  .name());
        } else {
          // Something funky happened
          fail();
        }
      }
      return "Success!";
    }
  }
}
