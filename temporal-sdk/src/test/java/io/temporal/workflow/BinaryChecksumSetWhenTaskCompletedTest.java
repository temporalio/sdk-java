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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.workflow.shared.*;
import org.junit.Rule;
import org.junit.Test;

public class BinaryChecksumSetWhenTaskCompletedTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setBinaryChecksum(SDKTestWorkflowRule.BINARY_CHECKSUM)
                  .build())
          .setWorkflowTypes(SimpleTestWorkflow.class)
          .build();

  @Test
  public void testBinaryChecksumSetWhenTaskCompleted() {
    TestWorkflows.TestWorkflow1 client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowExecution execution =
        WorkflowClient.start(client::execute, testWorkflowRule.getTaskQueue());
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    SDKTestWorkflowRule.waitForOKQuery(stub);
    History history = testWorkflowRule.getWorkflowExecutionHistory(execution);

    boolean foundCompletedTask = false;
    for (HistoryEvent event : history.getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
        assertEquals(
            SDKTestWorkflowRule.BINARY_CHECKSUM,
            event.getWorkflowTaskCompletedEventAttributes().getBinaryChecksum());
        foundCompletedTask = true;
      }
    }
    assertTrue(foundCompletedTask);
  }

  public static class SimpleTestWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class,
              ActivityOptions.newBuilder(TestOptions.newActivityOptionsForTaskQueue(taskQueue))
                  .build());
      testActivities.activity();
      return "done";
    }
  }
}
