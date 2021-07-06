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

package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DetachedScopeTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestDetachedCancellationScope.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testDetachedScope() {
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    client.start(testWorkflowRule.getTaskQueue());
    testWorkflowRule.sleep(Duration.ofSeconds(1)); // To let activityWithDelay start.
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    activitiesImpl.assertInvocations("activityWithDelay", "activity1", "activity2", "activity3");
  }

  public static class TestDetachedCancellationScope implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      try {
        testActivities.activityWithDelay(100000, true);
        fail("unreachable");
      } catch (ActivityFailure e) {
        assertTrue(e.getCause() instanceof CanceledFailure);
        Workflow.newDetachedCancellationScope(() -> assertEquals(1, testActivities.activity1(1)))
            .run();
      }
      try {
        Workflow.sleep(Duration.ofHours(1));
        fail("unreachable");
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> assertEquals("a12", testActivities.activity2("a1", 2)))
            .run();
      }
      try {
        Workflow.newTimer(Duration.ofHours(1)).get();
        fail("unreachable");
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> assertEquals("a123", testActivities.activity3("a1", 2, 3)))
            .run();
      }
      return "result";
    }
  }
}
