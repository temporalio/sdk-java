/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionSameIdOnReplayTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionSameIdOnReplay.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testGetVersionSameIdOnReplay() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    workflowStub.execute(testWorkflowRule.getTaskQueue());
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();

    // Validate that no marker is recorded
    GetWorkflowExecutionHistoryResponse response =
        testWorkflowRule
            .getTestEnvironment()
            .getWorkflowService()
            .blockingStub()
            .getWorkflowExecutionHistory(request);
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      Assert.assertFalse(EventType.EVENT_TYPE_MARKER_RECORDED == event.getEventType());
    }
  }

  public static class TestGetVersionSameIdOnReplay implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        Workflow.sleep(Duration.ofMinutes(1));
      } else {
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
        int version3 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 11);

        assertEquals(Workflow.DEFAULT_VERSION, version3);
        assertEquals(version2, version3);
      }

      return "test";
    }
  }
}
