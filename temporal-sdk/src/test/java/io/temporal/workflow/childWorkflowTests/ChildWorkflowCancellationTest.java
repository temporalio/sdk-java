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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowCancellationTest {

  private static final Signal detachedScopeFinished = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Before
  public void setUp() throws Exception {
    detachedScopeFinished.clearSignal();
  }

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
    assertFalse(
        "We should not have wait till the finish of detached scope in WAIT_CANCELLATION_REQUESTED mode",
        detachedScopeFinished.isSignalled());
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
    assertTrue(
        "We should be here after the full finish of the detached scope in WAIT_CANCELLATION_COMPLETED mode",
        detachedScopeFinished.isSignalled());
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
    assertFalse(
        "We should not have wait till the finish of detached scope in ABANDON mode",
        detachedScopeFinished.isSignalled());
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
    assertFalse(
        "We should not have wait till the finish of detached scope in TRY_CANCEL mode",
        detachedScopeFinished.isSignalled());
  }

  public static class TestParentWorkflowImpl implements TestWorkflows.TestWorkflowCancellationType {

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
      TestActivities.VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());
      try {
        Workflow.sleep(Duration.ofHours(1));
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> {
                  localActivities.sleepActivity(5000, 0);
                  detachedScopeFinished.signal();
                })
            .run();
      }
    }
  }
}
