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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class GetHistorySizeTest {
  private static final TestActivities.VariousTestActivities activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void replay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetHistorySize.json", TestWorkflowImpl.class);
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build();

      TestActivities.VariousTestActivities activities =
          Workflow.newLocalActivityStub(TestActivities.VariousTestActivities.class, options);

      assertEquals(408, Workflow.getInfo().getHistorySize());
      assertEquals(false, Workflow.getInfo().isContinueAsNewSuggested());

      // Force WFT heartbeat
      activities.sleepActivity(TimeUnit.SECONDS.toMillis(10), 1);

      assertEquals(897, Workflow.getInfo().getHistorySize());
      assertEquals(true, Workflow.getInfo().isContinueAsNewSuggested());

      return "done";
    }
  }
}
