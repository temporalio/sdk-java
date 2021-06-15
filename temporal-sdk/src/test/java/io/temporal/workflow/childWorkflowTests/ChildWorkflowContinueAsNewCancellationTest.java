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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowContinueAsNewCancellationTest {
  private static AtomicInteger count;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testChildWorkflowContinueAsNewCancellationTest() throws InterruptedException {
    count = new AtomicInteger(3);
    WorkflowStub parentWorkflow =
        testWorkflowRule.newUntypedWorkflowStub("TestWorkflowCancellationType");
    WorkflowExecution parentExecution =
        parentWorkflow.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    Thread.sleep(500);
    parentWorkflow.cancel();
    try {
      parentWorkflow.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    testWorkflowRule.assertHistoryEvent(
        parentExecution, EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED);
  }

  @Test
  public void testImposterWorkflowContinueAsNewCancellationTest() throws InterruptedException {
    count = new AtomicInteger(3);
    // Parent-child workflow
    WorkflowStub parentWorkflow =
        testWorkflowRule.newUntypedWorkflowStub("TestWorkflowCancellationType");
    WorkflowExecution parentExecution =
        parentWorkflow.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    // Imposter workflow
    WorkflowStub imposterChild = testWorkflowRule.newUntypedWorkflowStub("NoArgsWorkflow");
    WorkflowExecution execution = imposterChild.start();
    Thread.sleep(500);
    parentWorkflow.cancel();
    try {
      parentWorkflow.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    // Assert parent behavior
    // TODO: assert runID not set on this event and child workflow only boolean set to true
    testWorkflowRule.assertHistoryEvent(
        parentExecution, EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED);
    testWorkflowRule.assertHistoryEvent(
        parentExecution, EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED);
    // Assert imposter workflow behavior
    testWorkflowRule.assertNoHistoryEvent(
        execution, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED);
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
      Optional<String> id = Workflow.getInfo().getParentRunId();
      if (!id.isPresent()) {
        // Does not have parent
        Workflow.continueAsNew();
      } else if (count.getAndDecrement() > 0) {
        // Has parent
        if (count.get() == 0) {
          try {
            // Last child falls asleep
            Workflow.sleep(Duration.ofHours(1));
          } catch (CanceledFailure e) {
            System.out.println("Canceled child with runID: " + Workflow.getInfo().getRunId());
          }
        }
        Workflow.continueAsNew();
      }
    }
  }
}
