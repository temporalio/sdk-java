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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionDefaultInSignalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionDefaultInSignal() throws InterruptedException {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestSignaledWorkflow.class);
    WorkflowClient.start(workflow::execute);

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);

    workflow.signal(testWorkflowRule.getTaskQueue());
    workflow.signal(testWorkflowRule.getTaskQueue());
    testWorkflowRule.invalidateWorkflowCache();
    workflow.signal(testWorkflowRule.getTaskQueue());

    String result = workflowStub.getResult(String.class);
    assertEquals("1", result);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflows.TestSignaledWorkflow {
    int signalCounter = 0;

    @Override
    public String execute() {
      int version =
          io.temporal.workflow.Workflow.getVersion(
              "testMarker", io.temporal.workflow.Workflow.DEFAULT_VERSION, 1);
      Workflow.await(() -> signalCounter >= 3);
      return String.valueOf(version);
    }

    @Override
    public void signal(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      int version =
          io.temporal.workflow.Workflow.getVersion(
              "testMarker", io.temporal.workflow.Workflow.DEFAULT_VERSION, 1);
      if (version == 1) {
        testActivities.activity1(1);
      } else {
        testActivities.activity();
      }
      signalCounter++;
    }
  }
}
