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

package io.temporal.workflow.activityTests;

import com.google.common.base.Preconditions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LongLocalActivityWorkflowTaskHeartbeatFailureTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  private static final int REPLAY_COUNT = 2;
  private static final int ACTIVITY_SLEEP_SEC = 3;
  private static final int WORKFLOW_TASK_TIMEOUT_SEC = 2;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongLocalActivityWorkflowTaskHeartbeatFailureWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(REPLAY_COUNT * ACTIVITY_SLEEP_SEC + 10)
          .build();

  /**
   * Test that local activity that failed to heartbeat and executed longer than Workflow Task
   * Timeout will be repeated during replay
   */
  @Test
  public void testLongLocalActivityWorkflowTaskHeartbeatFailure() {
    Preconditions.checkState(
        ACTIVITY_SLEEP_SEC > WORKFLOW_TASK_TIMEOUT_SEC,
        "We make sure that activity sleeps longer than the workflow task timeout to emulate heartbeat failure");

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(WORKFLOW_TASK_TIMEOUT_SEC))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class, options);
    String result = workflowStub.execute();
    Assert.assertEquals("sleepActivity123", result);
    Assert.assertEquals(activitiesImpl.toString(), REPLAY_COUNT, activitiesImpl.invocations.size());
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatFailureWorkflowImpl
      implements TestWorkflows.TestWorkflowReturnString {

    static boolean invoked;

    @Override
    public String execute() {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());
      String result = localActivities.sleepActivity(ACTIVITY_SLEEP_SEC, 123);
      if (!invoked) {
        invoked = true;
        throw new Error("Simulate decision failure to force replay");
      }
      return result;
    }
  }
}
