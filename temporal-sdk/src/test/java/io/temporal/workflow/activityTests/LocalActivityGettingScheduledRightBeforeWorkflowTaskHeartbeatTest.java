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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.Config;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/1262")
public class LocalActivityGettingScheduledRightBeforeWorkflowTaskHeartbeatTest {
  private static final Duration WORKFLOW_TASK_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration SLEEP_DURATION =
      Duration.ofMillis(800); // << 1000 to don't hit deadlock detection

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(HeartbeatingWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test(timeout = 15_000)
  public void testLocalActivitiesWorkflowTaskHeartbeat() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(WORKFLOW_TASK_TIMEOUT.multipliedBy(2))
            .setWorkflowTaskTimeout(WORKFLOW_TASK_TIMEOUT)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    List<WorkflowStub> stubs = new ArrayList<>();

    // this test is actually pretty stable,
    // but run several instances to increase the chances of the right timing being hit
    for (int i = 0; i < 5; i++) {
      TestWorkflow1 workflow =
          testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
      WorkflowStub stub = WorkflowStub.fromTyped(workflow);
      stub.start(testWorkflowRule.getTaskQueue());
      stubs.add(stub);
    }

    for (WorkflowStub stub : stubs) {
      assertEquals("done", stub.getResult(String.class));
    }
  }

  public static class HeartbeatingWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());

      long firstLocalActivityDurationMs =
          (long) (WORKFLOW_TASK_TIMEOUT.toMillis() * Config.WORKFLOW_TAK_HEARTBEAT_COEFFICIENT)
              - SLEEP_DURATION.toMillis() / 2;
      localActivities.sleepActivity(firstLocalActivityDurationMs, 0);

      // It is very important for reproduction that the workflow heartbeat timeout is reached DURING
      // this sleep / workflow code execution.
      // So the first local activity is done, eventLoop is triggered and heartbeat timeout is
      // reached at the end of
      // this workflow code event loop call with the next activity scheduled.
      try {
        Thread.sleep(SLEEP_DURATION.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }

      localActivities.sleepActivity(TimeUnit.SECONDS.toMillis(1), 0);

      return "done";
    }
  }
}
