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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.ExampleWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class SignalTerminatedWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class, SignalingWorkflowImpl.class)
          .build();

  @Test
  public void testTerminateWorkflowSignalError() {
    System.out.println("Client starting workflow 1:");
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId("abc123")
            .build();
    ExampleWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(ExampleWorkflow.class, options);
    WorkflowClient.start(workflow::execute, testWorkflowRule.getTaskQueue());

    testWorkflowRule.sleep(Duration.ofSeconds(7));

    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.terminate("Mock terminating workflow");

    TestWorkflowReturnString signalingWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnString.class);
    signalingWorkflow.execute();

    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(signalingWorkflow);
    assertEquals(workflowStub2.getResult(String.class), "Success!");
  }

  public static class WorkflowImpl implements ExampleWorkflow {
    private boolean signal = false;

    @Override
    public String execute(String s) {
      Workflow.await(() -> signal);
      Workflow.sleep(Duration.ofSeconds(15));
      return s;
    }

    @Override
    public void signal(boolean signal) {
      this.signal = signal;
    }
  }

  public static class SignalingWorkflowImpl implements TestWorkflowReturnString {
    @Override
    public String execute() {
      try {
        ExampleWorkflow correspondingWorkflow =
            Workflow.newExternalWorkflowStub(ExampleWorkflow.class, "abc123");
        correspondingWorkflow.signal(true);
      } catch (ApplicationFailure e) {
        // ignore
      }
      return "Success!";
    }
  }
}
