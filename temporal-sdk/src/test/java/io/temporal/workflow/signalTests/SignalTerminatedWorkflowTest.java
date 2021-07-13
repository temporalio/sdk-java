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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.Rule;
import org.junit.Test;

public class SignalTerminatedWorkflowTest {

  private static String WORKFLOW_ID = "testWorkflowID";
  private static String TEST_SIGNAL = "test";
  private static CountDownLatch latch = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class, SignalingWorkflowImpl.class)
          .build();

  @Test
  public void testTerminateWorkflowSignalError() throws InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId(WORKFLOW_ID)
            .build();
    TestSignaledWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowClient.start(workflow::execute);

    latch.await();

    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.terminate("Mock terminating workflow");

    TestWorkflowReturnString signalingWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnString.class);
    WorkflowClient.start(signalingWorkflow::execute);

    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(signalingWorkflow);
    assertEquals(workflowStub2.getResult(String.class), "Success!");
  }

  public static class WorkflowImpl implements TestSignaledWorkflow {
    private String signal;

    @Override
    public String execute() {
      Workflow.await(Duration.ofSeconds(1), () -> signal == TEST_SIGNAL);
      latch.countDown();
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
        TestSignaledWorkflow stub =
            Workflow.newExternalWorkflowStub(TestSignaledWorkflow.class, WORKFLOW_ID);
        stub.signal(TEST_SIGNAL);
        fail();
      } catch (ApplicationFailure e) {
        assertEquals(
            e.getType(),
            SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND
                .name());
      }
      return "Success!";
    }
  }
}
