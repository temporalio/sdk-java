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
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowCancellationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowContinueAsNewCancellationScopeTest {
  private static AtomicInteger count;
  private static CountDownLatch latch = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testChildWorkflowContinueAsNewCancellationTest() throws InterruptedException {
    count = new AtomicInteger(2);
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
    NoArgsWorkflow imposterChild =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId("WorkflowID")
                    .build());
    System.out.println("Waiting for the first child workflow to finish...");
    latch.await();
    System.out.println("First child workflow has finished, starting the impostor.");
    imposterChild.execute();
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
  }

  public static class TestParentWorkflowImpl implements TestWorkflowCancellationType {
    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(
              NoArgsWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId("WorkflowID")
                  .setCancellationType(cancellationType)
                  .build());
      // Create CancellationScope.
      List<Promise<Void>> children = new ArrayList<>();
      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> {
                Promise<Void> promise = Async.procedure(child::execute);
                children.add(promise);
              });
      scope.run();
      Promise.allOf(children).get();
      // Trigger cancellation of the uncompleted child invocation within the cancellation scope
      scope.cancel();
    }
  }

  public static class TestChildWorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      Optional<String> parentRunId = Workflow.getInfo().getParentRunId();
      if (!parentRunId.isPresent()) {
        // Does not have parent
        Workflow.await(() -> false);
      } else if (count.getAndDecrement() > 0) {
        // Has parent
        if (count.get() == 0) {
          try {
            // Last child falls asleep
            Workflow.sleep(Duration.ofHours(1));
          } catch (CanceledFailure e) {
          }
        }
        // Signal that the workflow has finished.
        latch.countDown();
        Workflow.continueAsNew();
      }
    }
  }
}
