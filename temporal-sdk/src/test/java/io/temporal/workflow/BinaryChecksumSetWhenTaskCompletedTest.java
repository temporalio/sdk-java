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
import static org.junit.Assert.assertNotNull;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Rule;
import org.junit.Test;

public class BinaryChecksumSetWhenTaskCompletedTest {
  private static final String BINARY_CHECKSUM = "testChecksum";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setBinaryChecksum(BINARY_CHECKSUM).build())
          .setWorkflowTypes(SimpleTestWorkflow.class)
          .build();

  @Test
  public void testBinaryChecksumSetWhenTaskCompleted() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowExecution execution =
        WorkflowClient.start(client::execute, testWorkflowRule.getTaskQueue());
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    SDKTestWorkflowRule.waitForOKQuery(stub);

    HistoryEvent completionEvent =
        testWorkflowRule.getHistoryEvent(execution, EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED);
    assertNotNull(completionEvent);
    assertEquals(
        BINARY_CHECKSUM,
        completionEvent.getWorkflowTaskCompletedEventAttributes().getBinaryChecksum());
  }

  public static class SimpleTestWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              ActivityOptions.newBuilder(SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue))
                  .build());
      testActivities.activity();
      return "done";
    }
  }
}
