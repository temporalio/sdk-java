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

package io.temporal.activity;

import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow3;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowImplementationOptionsTest {

  ActivityOptions defaultOps =
      ActivityOptions.newBuilder()
          // .setTaskQueue("DefaultActivityOptions")
          .setHeartbeatTimeout(Duration.ofSeconds(1))
          .setScheduleToCloseTimeout(Duration.ofDays(1))
          .setStartToCloseTimeout(Duration.ofSeconds(3))
          // .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
          // .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
          .build();

  ActivityOptions activityOps2 =
      ActivityOptions.newBuilder()
          // .setTaskQueue("Activity1MethodOps")
          .setHeartbeatTimeout(Duration.ofSeconds(5))
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .build();

  Map<String, ActivityOptions> activity2MethodOptions =
      new HashMap<String, ActivityOptions>() {
        {
          put("Activity2", activityOps2);
        }
      };

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setDefaultActivityOptions(defaultOps)
                  .setActivityOptions(activity2MethodOptions)
                  .build(),
              TestSetDefaultActivityOptions.class)
          .setActivityImplementations(new TestActivityImpl())
          // .setTestTimeoutSeconds(1000)
          .build();

  @Test
  public void testSetDefaultActivityOptionsTest() {
    TestWorkflow3 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow3.class);
    Map<String, Map<String, Duration>> result = workflowStub.execute();

    // Check that options for method1 were merged.
    Map<String, Duration> activity1Values = result.get("Activity1");
    Assert.assertEquals(defaultOps.getHeartbeatTimeout(), activity1Values.get("HeartbeatTimeout"));
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), activity1Values.get("ScheduleToCloseTimeout"));
    Assert.assertEquals(
        defaultOps.getStartToCloseTimeout(), activity1Values.get("StartToCloseTimeout"));

    // Check that options for method2 were default.
    Map<String, Duration> activity2Values = result.get("Activity2");
    Assert.assertEquals(
        activityOps2.getHeartbeatTimeout(), activity2Values.get("HeartbeatTimeout"));
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), activity2Values.get("ScheduleToCloseTimeout"));
    Assert.assertEquals(
        activityOps2.getStartToCloseTimeout(), activity2Values.get("StartToCloseTimeout"));
  }

  public static class TestSetDefaultActivityOptions implements TestWorkflow3 {

    @Override
    public Map<String, Map<String, Duration>> execute() {
      Map<String, Map<String, Duration>> result = new HashMap<>();
      TestActivity activities = Workflow.newActivityStub(TestActivity.class);
      result.put("Activity1", activities.activity1());
      result.put("Activity2", activities.activity2());
      return result;
    }
  }
}
