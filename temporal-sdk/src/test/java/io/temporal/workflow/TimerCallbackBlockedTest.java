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

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TimerCallbackBlockedTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestTimerCallbackBlockedWorkflowImpl.class)
          .build();

  /** Test that it is not allowed to block in the timer callback thread. */
  @Test
  public void testTimerCallbackBlocked() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("timer2Fired", result);
  }

  public static class TestTimerCallbackBlockedWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Promise<Void> timer1 = Workflow.newTimer(Duration.ZERO);
      Promise<Void> timer2 = Workflow.newTimer(Duration.ofSeconds(1));

      return timer1
          .thenApply(
              e -> {
                timer2.get();
                return "timer2Fired";
              })
          .get();
    }
  }
}
