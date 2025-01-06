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

package io.temporal.activity;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class ActivityHeartbeatSentOnFailureTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new HeartBeatingActivityImpl())
          .build();

  /** Tests that the last Activity#heartbeat value is sent if the activity fails. */
  @Test
  public void activityHeartbeatSentOnFailure() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    workflow.execute();
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {

    private final TestActivities.NoArgsActivity activities =
        Workflow.newActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public void execute() {
      activities.execute();
    }
  }

  public static class HeartBeatingActivityImpl implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      // If the heartbeat details are "3", then we know that the last heartbeat was sent.
      if (Activity.getExecutionContext().getHeartbeatDetails(String.class).orElse("").equals("3")) {
        return;
      }
      // Send 3 heartbeats and then fail, expecting the last heartbeat to be sent
      // even though the activity fails and the last two attempts would normally be throttled.
      Activity.getExecutionContext().heartbeat("1");
      Activity.getExecutionContext().heartbeat("2");
      Activity.getExecutionContext().heartbeat("3");
      throw new RuntimeException("simulated failure");
    }
  }
}
