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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnMap;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityOptionsPrecedenceTest {

  private static final LocalActivityOptions implOptions =
      LocalActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofHours(10))
          .setScheduleToCloseTimeout(Duration.ofDays(10))
          .build();

  private static final Map<String, LocalActivityOptions> workflowOptionsMap =
      Collections.singletonMap(
          "Activity1",
          LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofHours(20)).build());

  private static final Map<String, LocalActivityOptions> stubOptionsMap =
      Collections.singletonMap(
          "Activity2",
          LocalActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofDays(30)).build());

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setDefaultLocalActivityOptions(implOptions)
                  .build(),
              TestSetDefaultLocalActivityOptionsWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @Test
  public void testSetWorkflowImplementationOptions() {
    TestWorkflowReturnMap workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflowReturnMap.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    Map<String, Map<String, Duration>> result = workflowStub.execute();

    Map<String, Duration> activity1Values = result.get("Activity1");
    Duration a1StartToClose = activity1Values.get("StartToCloseTimeout");
    Duration a1ScheduleToClose = activity1Values.get("ScheduleToCloseTimeout");

    assertEquals(workflowOptionsMap.get("Activity1").getStartToCloseTimeout(), a1StartToClose);
    assertEquals(implOptions.getScheduleToCloseTimeout(), a1ScheduleToClose);

    Map<String, Duration> activity2Values = result.get("Activity2");
    Duration a2StartToClose = activity2Values.get("StartToCloseTimeout");
    Duration a2ScheduleToClose = activity2Values.get("ScheduleToCloseTimeout");

    assertEquals(implOptions.getStartToCloseTimeout(), a2StartToClose);
    assertEquals(stubOptionsMap.get("Activity2").getScheduleToCloseTimeout(), a2ScheduleToClose);
  }

  public static class TestSetDefaultLocalActivityOptionsWorkflowImpl
      implements TestWorkflowReturnMap {
    @Override
    public Map<String, Map<String, Duration>> execute() {
      Workflow.applyLocalActivityOptions(workflowOptionsMap);
      TestActivity activities =
          Workflow.newLocalActivityStub(TestActivity.class, null, stubOptionsMap);

      Map<String, Map<String, Duration>> result = new HashMap<>();
      result.put("Activity1", activities.activity1());
      result.put("Activity2", activities.activity2());
      return result;
    }
  }
}
