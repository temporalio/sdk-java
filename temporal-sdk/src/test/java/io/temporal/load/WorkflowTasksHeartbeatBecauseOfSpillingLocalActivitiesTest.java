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

package io.temporal.load;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.activityTests.LongLocalActivityWorkflowTaskHeartbeatTest;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

// @Ignore("Load tests shouldn't run as a part of build")
public class WorkflowTasksHeartbeatBecauseOfSpillingLocalActivitiesTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(HeartbeatingWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  /**
   * Emulates a workflow that triggers several relatively short consecutive local activities (that
   * sleep less than Workflow Task Timeout) that together process (or sleep) longer than Workflow
   * Task Timeout. Such consecutive local activities will be executed as a part of one big Workflow
   * Task.
   *
   * <p>This test makes sure than such a workflow and workflow task is not getting timed out by
   * performing heartbeats even while it takes longer than Workflow Task Timeout.
   *
   * @see LongLocalActivityWorkflowTaskHeartbeatTest
   */
  @Test(timeout = 30_000)
  public void testLocalActivitiesWorkflowTaskHeartbeat() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(15))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    List<WorkflowStub> stubs = new ArrayList<>();

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

      localActivities.sleepActivity(7_500, 0);

      // this is a very important for the reproduction. It hits the spot when the last local
      // activity is done, eventLoop is triggered and heartbeat timeout is reached at the end of
      // this WFT with the next activity to be processed. So, the heartbeat timeout is reached
      // DURING this sleep / workflow code execution.
      try {
        Thread.sleep(800);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }

      localActivities.sleepActivity(TimeUnit.SECONDS.toMillis(1), 0);

      return "done";
    }
  }
}
