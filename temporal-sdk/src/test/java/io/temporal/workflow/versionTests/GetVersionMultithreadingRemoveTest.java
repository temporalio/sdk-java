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

package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionMultithreadingRemoveTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .setUseExternalService(true)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionMultithreadingRemoval() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("activity1", result);
  }

  @Test
  public void testGetVersionMultithreadingRemovalReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionMultithreadingRemoveHistory.json", TestGetVersionWorkflowImpl.class);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities.VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      Async.procedure(
          () -> {
            Workflow.sleep(1000);
          });

      // Test removing a version check in replaying code with an additional thread running.
      if (!WorkflowUnsafe.isReplaying()) {
        int version = Workflow.getVersion("changeId", 1, 2);
        assertEquals(version, 2);
      } else {
        hasReplayed = true;
      }
      String result =
          "activity" + testActivities.activity1(1); // This is executed in non-replay mode.
      return result;
    }
  }
}
