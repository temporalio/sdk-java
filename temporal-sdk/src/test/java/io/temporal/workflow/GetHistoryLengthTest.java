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
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class GetHistoryLengthTest {
  private static final TestActivities.VariousTestActivities activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test(timeout = 20000)
  public void getHistoryLength() {
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflowReturnString.class);
    assertEquals("done", workflowStub.execute());

    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    testWorkflowRule.regenerateHistoryForReplay(execution.getWorkflowId(), "testGetHistoryLength");
  }

  @Test
  public void replay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetHistoryLength.json", TestWorkflowImpl.class);
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

      assertEquals(3, Workflow.getInfo().getHistoryLength());

      // Force WFT heartbeat
      activities.sleepActivity(TimeUnit.SECONDS.toMillis(10), 1);

      assertEquals(9, Workflow.getInfo().getHistoryLength());

      return "done";
    }
  }
}
