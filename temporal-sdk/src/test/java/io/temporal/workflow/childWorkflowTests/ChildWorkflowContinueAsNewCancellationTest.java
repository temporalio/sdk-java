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
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestChildWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowContinueAsNewCancellationTest {
  private static final AtomicInteger count = new AtomicInteger(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  public void testChildWorkflowCancellationTryCancel() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStub("TestWorkflow");
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.TRY_CANCEL);
    testWorkflowRule.sleep(Duration.ofSeconds(3));
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
      System.out.println("CANCELLED!");
    }
    testWorkflowRule.assertHistoryEvent(
        execution, EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED);
  }

  public static class TestParentWorkflowImpl implements TestWorkflow {
    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      TestChildWorkflow child =
          Workflow.newChildWorkflowStub(
              TestChildWorkflow.class,
              ChildWorkflowOptions.newBuilder().setCancellationType(cancellationType).build());
      child.execute();
    }
  }

  public static class TestChildWorkflowImpl implements TestChildWorkflow {
    @Override
    public void execute() {
      count.incrementAndGet();
      System.out.println("ChildWorkflow runID: " + Workflow.getInfo().getRunId());
      Workflow.sleep(Duration.ofSeconds(1));
      Workflow.continueAsNew();
    }
  }
}
