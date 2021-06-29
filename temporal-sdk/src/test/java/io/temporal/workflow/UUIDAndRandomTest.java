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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;

public class UUIDAndRandomTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .setWorkflowTypes(TestUUIDAndRandom.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testUUIDAndRandom() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("foo10", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "sideEffect",
            "sideEffect",
            "executeActivity Activity2",
            "activity Activity2");
  }

  public static class TestUUIDAndRandom implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      hasReplayed = Workflow.isReplaying();
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      Random rand1 = Workflow.newRandom();
      int r11 = rand1.nextInt();
      int r12 = r11 + rand1.nextInt();
      int savedInt = Workflow.sideEffect(int.class, () -> r12);
      String id = Workflow.randomUUID().toString() + "-" + Workflow.randomUUID().toString();
      String savedId = Workflow.sideEffect(String.class, () -> id);

      // Invoke activity in a blocking mode to ensure that asserts run after replay.
      String result = activities.activity2("foo", 10);

      // Assert that during replay values didn't change.
      if (hasReplayed) {
        assertEquals(savedId, id);
        assertEquals(savedInt, r12);
      }
      return result;
    }
  }
}
