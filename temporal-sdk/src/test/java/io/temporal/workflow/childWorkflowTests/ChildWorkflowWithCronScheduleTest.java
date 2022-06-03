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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflowWithCronScheduleImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowWithCronSchedule;
import java.time.Duration;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ChildWorkflowWithCronScheduleTest {

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCronParentWorkflow.class, TestWorkflowWithCronScheduleImpl.class)
          .build();

  @Test
  public void testChildWorkflowWithCronSchedule() {
    // Min interval in cron is 1min. So we will not test it against real service in Jenkins.
    // Feel free to uncomment the line below and test in local.
    assumeFalse("skipping as test will timeout", SDKTestWorkflowRule.useExternalService);

    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofDays(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    WorkflowStub client =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestWorkflow1", workflowOptions);
    client.start(testName.getMethodName());
    testWorkflowRule.getTestEnvironment().sleep(Duration.ofHours(3));
    client.cancel();

    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    // Run 3 failed. So on run 4 we get the last completion result from run 2.
    Map<Integer, String> lastCompletionResults =
        TestWorkflowWithCronScheduleImpl.lastCompletionResults.get(testName.getMethodName());
    assertEquals("run 2", lastCompletionResults.get(4));
  }

  public static class TestCronParentWorkflow implements TestWorkflow1 {
    private final TestWorkflowWithCronSchedule cronChild =
        Workflow.newChildWorkflowStub(TestWorkflowWithCronSchedule.class);

    @Override
    public String execute(String testName) {
      return cronChild.execute(testName);
    }
  }
}
