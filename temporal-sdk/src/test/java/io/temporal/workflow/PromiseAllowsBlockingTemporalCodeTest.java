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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests that a call to an activity is allowed in the Promise handler. It's technically is blocking
 * operation. And blocking operations are disallowed in Workflow code. But because it's one of the
 * "Temporal SDK" blocking operations, it's allowed.
 */
public class PromiseAllowsBlockingTemporalCodeTest {
  private static final TestActivities.VariousTestActivities activities =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflow.class)
          .setActivityImplementations(activities)
          .build();

  @Test
  public void testChildWorkflowExecutionPromiseHandler() {
    WorkflowClient workflowStub = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowReturnString client =
        workflowStub.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class, options);
    String result = client.execute();
    assertEquals("sleepFinished", result);
  }

  public static class TestWorkflow implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      TestActivities.VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(Workflow.getInfo().getTaskQueue()));

      return Async.function(testActivities::sleepActivity, 50L, 0)
          .thenApply(
              (ignore) -> {
                // 3000ms is more than wft timeout of 2s set earlier on WorkflowOptions
                testActivities.sleepActivity(3000, 0);
                return "sleepFinished";
              })
          .get();
    }
  }
}
