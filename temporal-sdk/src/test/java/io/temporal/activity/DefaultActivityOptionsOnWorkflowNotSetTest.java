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
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import io.temporal.workflow.shared.TestActivities.TestLocalActivity;
import io.temporal.workflow.shared.TestActivities.TestLocalActivityImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnMap;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class DefaultActivityOptionsOnWorkflowNotSetTest {
  private final ActivityOptions defaultOps = SDKTestOptions.newActivityOptions20sScheduleToClose();
  private final LocalActivityOptions defaultLocalOps =
      SDKTestOptions.newLocalActivityOptions20sScheduleToClose();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.getDefaultInstance(),
              TestSetNullActivityOptionsWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl(), new TestLocalActivityImpl())
          .build();

  @Test
  public void testDefaultActivityOptionsNotSetTest() {
    TestWorkflowReturnMap workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowReturnMap.class);
    Map<String, Map<String, Duration>> result = workflowStub.execute();

    // Check that both activities have options passed in the stub.
    Map<String, Duration> activity1Values = result.get("Activity1");
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), activity1Values.get("ScheduleToCloseTimeout"));
    // If not set, Temporal service sets to ScheduleToCloseTimeout value
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), activity1Values.get("StartToCloseTimeout"));

    Map<String, Duration> activity2Values = result.get("Activity2");
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), activity2Values.get("ScheduleToCloseTimeout"));
    // If not set, Temporal service sets to ScheduleToCloseTimeout value
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), activity2Values.get("StartToCloseTimeout"));
  }

  @Ignore("Pending fix to Local Activity cancellations to use startToCloseTimeout") // TODO
  @Test
  public void testDefaultLocalActivityOptionsNotSetTest() {
    TestWorkflowReturnMap workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowReturnMap.class);
    Map<String, Map<String, Duration>> result = workflowStub.execute();

    // Check that both activities have options passed in the stub.
    Map<String, Duration> localActivity1Values = result.get("LocalActivity1");
    Assert.assertEquals(
        defaultLocalOps.getScheduleToCloseTimeout(),
        localActivity1Values.get("ScheduleToCloseTimeout"));
    // If not set, Temporal service sets to ScheduleToCloseTimeout value
    Assert.assertEquals(
        defaultLocalOps.getScheduleToCloseTimeout(),
        localActivity1Values.get("StartToCloseTimeout"));

    Map<String, Duration> localActivity2Values = result.get("LocalActivity2");
    Assert.assertEquals(
        defaultLocalOps.getScheduleToCloseTimeout(),
        localActivity2Values.get("ScheduleToCloseTimeout"));
    // If not set, Temporal service sets to ScheduleToCloseTimeout value
    Assert.assertEquals(
        defaultLocalOps.getScheduleToCloseTimeout(),
        localActivity2Values.get("StartToCloseTimeout"));
  }

  public static class TestSetNullActivityOptionsWorkflowImpl implements TestWorkflowReturnMap {
    @Override
    public Map<String, Map<String, Duration>> execute() {
      Map<String, Map<String, Duration>> result = new HashMap<>();
      TestActivity activities =
          Workflow.newActivityStub(
              TestActivity.class, SDKTestOptions.newActivityOptions20sScheduleToClose());
      TestLocalActivity localActivities =
          Workflow.newLocalActivityStub(
              TestLocalActivity.class, SDKTestOptions.newLocalActivityOptions20sScheduleToClose());
      result.put("Activity1", activities.activity1());
      result.put("Activity2", activities.activity2());
      result.put("LocalActivity1", localActivities.localActivity1());
      result.put("LocalActivity2", localActivities.localActivity2());
      return result;
    }
  }
}
