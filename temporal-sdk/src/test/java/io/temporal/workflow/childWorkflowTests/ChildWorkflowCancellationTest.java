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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowCancellationType;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowCancellationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testChildWorkflowWaitCancellationRequested() {
    WorkflowStub client =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowCancellationType");
    WorkflowExecution execution =
        client.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    testWorkflowRule.assertHistoryEvent(
        execution, EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED);
    testWorkflowRule.assertNoHistoryEvent(
        execution, EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED);
  }

  @Test
  public void testChildWorkflowWaitCancellationCompleted() {
    WorkflowStub client =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowCancellationType");
    WorkflowExecution execution =
        client.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    testWorkflowRule.assertHistoryEvent(
        execution, EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED);
  }

  @Test
  public void testChildWorkflowCancellationAbandon() {
    WorkflowStub client =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowCancellationType");
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.ABANDON);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    testWorkflowRule.assertNoHistoryEvent(
        execution, EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED);
  }

  @Test
  public void testChildWorkflowCancellationTryCancel() {
    WorkflowStub client =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowCancellationType");
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.TRY_CANCEL);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    testWorkflowRule.assertHistoryEvent(
        execution, EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED);
    testWorkflowRule.assertNoHistoryEvent(
        execution, EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED);
  }

  public static class TestParentWorkflowImpl implements TestWorkflowCancellationType {

    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(
              NoArgsWorkflow.class,
              ChildWorkflowOptions.newBuilder().setCancellationType(cancellationType).build());
      child.execute();
    }
  }

  public static class TestChildWorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      try {
        Workflow.sleep(Duration.ofHours(1));
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(() -> Workflow.sleep(Duration.ofSeconds(1))).run();
      }
    }
  }
}
