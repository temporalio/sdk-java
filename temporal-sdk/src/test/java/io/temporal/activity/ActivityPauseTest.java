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

import static org.junit.Assume.assumeTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflowservice.v1.PauseActivityRequest;
import io.temporal.client.ActivityPausedException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityPauseTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new HeartBeatingActivityImpl())
          .build();

  @Test
  public void activityPause() {
    assumeTrue(
        "Test Server doesn't support activity pause", SDKTestWorkflowRule.useExternalService);

    TestWorkflows.TestWorkflowReturnString workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    Assert.assertEquals("I am stopped by Pause", workflow.execute());
    Assert.assertEquals(
        1,
        WorkflowStub.fromTyped(workflow)
            .describe()
            .getRawDescription()
            .getPendingActivitiesCount());
    PendingActivityInfo activityInfo =
        WorkflowStub.fromTyped(workflow).describe().getRawDescription().getPendingActivities(0);
    Assert.assertTrue(activityInfo.getPaused());
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    private final TestActivities.TestActivity1 activities =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public String execute() {
      Async.function(activities::execute, "");
      Workflow.sleep(Duration.ofSeconds(1));
      return activities.execute("CompleteOnPause");
    }
  }

  public static class HeartBeatingActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String arg) {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      // Have the activity pause itself
      Activity.getExecutionContext()
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .pauseActivity(
              PauseActivityRequest.newBuilder()
                  .setNamespace(info.getNamespace())
                  .setExecution(
                      WorkflowExecution.newBuilder().setWorkflowId(info.getWorkflowId()).build())
                  .setId(info.getActivityId())
                  .build());
      while (true) {
        try {
          Thread.sleep(1000);
          // Heartbeat and verify that the correct exception is thrown
          Activity.getExecutionContext().heartbeat("1");
        } catch (ActivityPausedException pe) {
          if (arg.equals("CompleteOnPause")) {
            // An activity should be able to succeed if paused
            return "I am stopped by Pause";
          }
          // This will fail the attempt, and the activity will not be retried if not unpaused
          throw pe;
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
}
