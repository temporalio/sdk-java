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

package io.temporal.client.functional;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class CancelTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(AwaitingWorkflow.class).build();

  @Test
  public void cancellationOfNonExistentWorkflow() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflow1.class, UUID.randomUUID().toString());
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    assertThrows(WorkflowNotFoundException.class, untyped::cancel);
  }

  // Testing the current behavior, this test MAY break after fixing:
  // https://github.com/temporalio/temporal/issues/2860
  @Test
  public void secondCancellationImmediately() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.cancel();
    untyped.cancel();
  }

  // Testing the current behavior, this test WILL break after fixing:
  // https://github.com/temporalio/temporal/issues/2860
  @Test
  public void secondCancellationAfterWorkflowIsCancelled() {
    TestWorkflows.TestWorkflow1 workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.cancel();

    // wait till cancellation is confirmed and the workflow is cancelled
    WorkflowFailedException workflowFailedException =
        assertThrows(WorkflowFailedException.class, () -> untyped.getResult(String.class));
    Throwable cause = workflowFailedException.getCause();
    assertThat(cause, is(instanceOf(CanceledFailure.class)));

    untyped.cancel();
  }

  public static class AwaitingWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.await(() -> false);
      return "success";
    }
  }
}
