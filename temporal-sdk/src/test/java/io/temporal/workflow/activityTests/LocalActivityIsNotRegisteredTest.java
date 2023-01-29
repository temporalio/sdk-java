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

import static org.junit.Assert.fail;

import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityIsNotRegisteredTest {
  private final VariousTestActivities activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testUnregisteredLocalActivityDoesntFailExecution() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(20))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflowReturnString workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflowReturnString.class, options);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.start();
    testWorkflowRule.waitForTheEndOfWFT(stub.getExecution().getWorkflowId());
    testWorkflowRule.assertHistoryEvent(
        stub.getExecution().getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    testWorkflowRule.assertNoHistoryEvent(
        stub.getExecution().getWorkflowId(), EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED);
    testWorkflowRule.assertNoHistoryEvent(
        stub.getExecution().getWorkflowId(),
        EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED);
  }

  public static class TestWorkflowImpl implements TestWorkflowReturnString {
    @Override
    public String execute() {
      // TestLocalActivity implementation is not registered with the worker.
      // Only VariousTestActivities does.
      TestActivities.TestLocalActivity localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.TestLocalActivity.class, SDKTestOptions.newLocalActivityOptions());

      localActivities.localActivity1();
      fail();
      return "done";
    }
  }
}
