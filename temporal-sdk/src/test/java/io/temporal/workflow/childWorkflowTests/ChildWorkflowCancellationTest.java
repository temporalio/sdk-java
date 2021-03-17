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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowTest;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowCancellationTest {
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testChildWorkflowWaitCancellationRequested() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow");
    WorkflowExecution execution =
        client.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        testWorkflowRule
            .getTestEnvironment()
            .getWorkflowService()
            .blockingStub()
            .getWorkflowExecutionHistory(request);

    boolean hasChildCanceled = false;
    boolean hasChildCancelRequested = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
        hasChildCanceled = true;
      }
      if (event.getEventType()
          == EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
        hasChildCancelRequested = true;
      }
    }
    Assert.assertTrue(hasChildCancelRequested);
    Assert.assertFalse(hasChildCanceled);
  }

  @Test
  public void testChildWorkflowWaitCancellationCompleted() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow");
    WorkflowExecution execution =
        client.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        testWorkflowRule
            .getTestEnvironment()
            .getWorkflowService()
            .blockingStub()
            .getWorkflowExecutionHistory(request);

    boolean hasChildCanceled = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
        hasChildCanceled = true;
      }
    }
    Assert.assertTrue(hasChildCanceled);
  }

  @Test
  public void testChildWorkflowCancellationAbandon() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow");
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.ABANDON);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        testWorkflowRule
            .getTestEnvironment()
            .getWorkflowService()
            .blockingStub()
            .getWorkflowExecutionHistory(request);

    boolean hasChildCancelInitiated = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType()
          == EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
        hasChildCancelInitiated = true;
      }
    }
    Assert.assertFalse(hasChildCancelInitiated);
  }

  @Test
  public void testChildWorkflowCancellationTryCancel() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow");
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.TRY_CANCEL);
    testWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        testWorkflowRule
            .getTestEnvironment()
            .getWorkflowService()
            .blockingStub()
            .getWorkflowExecutionHistory(request);

    boolean hasChildCancelInitiated = false;
    boolean hasChildCancelRequested = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType()
          == EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
        hasChildCancelInitiated = true;
      }
      if (event.getEventType()
          == EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
        hasChildCancelRequested = true;
      }
    }
    Assert.assertTrue(hasChildCancelInitiated);
    Assert.assertFalse(hasChildCancelRequested);
  }

  public static class TestParentWorkflowImpl implements WorkflowTest.TestWorkflow {

    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      WorkflowTest.TestChildWorkflow child =
          Workflow.newChildWorkflowStub(
              WorkflowTest.TestChildWorkflow.class,
              ChildWorkflowOptions.newBuilder().setCancellationType(cancellationType).build());
      child.execute();
    }
  }

  public static class TestChildWorkflowImpl implements WorkflowTest.TestChildWorkflow {
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
