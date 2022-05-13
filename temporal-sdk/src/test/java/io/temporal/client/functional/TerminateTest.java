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

package io.temporal.client.functional;

import static org.junit.Assert.assertThrows;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class TerminateTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(AwaitingWorkflow.class).build();

  @Test
  public void terminationOfNonExistentWorkflow() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflow1.class, UUID.randomUUID().toString());
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    assertThrows(WorkflowNotFoundException.class, () -> untyped.terminate("termination"));
  }

  @Test
  public void doubleTermination() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.terminate("termination");
    assertThrows(WorkflowNotFoundException.class, () -> untyped.terminate("termination"));
  }

  @Test
  public void absentReasonIsAllowed() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.terminate(null);
  }

  public static class AwaitingWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.await(() -> false);
      return "success";
    }
  }
}
