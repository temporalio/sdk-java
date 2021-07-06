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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowTaskFailureBackoffTest {

  private static int testWorkflowTaskFailureBackoffReplayCount;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowTaskFailureBackoff.class)
          .build();

  @Test
  public void testWorkflowTaskFailureBackoff() {
    testWorkflowTaskFailureBackoffReplayCount = 0;
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    long start = testWorkflowRule.getTestEnvironment().currentTimeMillis();
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    long elapsed = testWorkflowRule.getTestEnvironment().currentTimeMillis() - start;
    Assert.assertTrue("spinned on fail workflow task", elapsed > 1000);
    Assert.assertEquals("result1", result);
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();

    Assert.assertEquals(
        1,
        testWorkflowRule
            .getHistoryEvents(execution, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED)
            .size());
  }

  public static class TestWorkflowTaskFailureBackoff implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      if (testWorkflowTaskFailureBackoffReplayCount++ < 2) {
        throw new Error("simulated workflow task failure");
      }
      return "result1";
    }
  }
}
