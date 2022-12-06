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

package io.temporal.workflow.activityTests;

import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivitySuccessfulCompletionTest {
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testSuccessfulCompletion() {
    TestWorkflows.TestWorkflowReturnString workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    String result = workflowStub.execute();
    Assert.assertEquals("input1", result);
    Assert.assertEquals(activitiesImpl.toString(), 1, activitiesImpl.invocations.size());
    testWorkflowRule.regenerateHistoryForReplay(
        WorkflowStub.fromTyped(workflowStub).getExecution(), "laSuccessfulCompletion_1_xx");
  }

  /** History from 1.17 before we changed LA marker structure in 1.18 */
  @Test
  public void testSuccessfulCompletion_replay117() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "laSuccessfulCompletion_1_17.json",
        TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class);
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      TestActivities.VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newLocalActivityOptions20sScheduleToClose());
      return localActivities.activity2("input", 1);
    }
  }
}
