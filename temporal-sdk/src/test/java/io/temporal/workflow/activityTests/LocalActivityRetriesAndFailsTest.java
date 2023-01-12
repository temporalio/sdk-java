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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertThrows;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.io.IOException;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityRetriesAndFailsTest {
  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityRetry.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testLocalActivityRetriesAndFails() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowException e =
        assertThrows(
            WorkflowException.class, () -> workflowStub.execute(testWorkflowRule.getTaskQueue()));

    Assert.assertTrue(e.getCause() instanceof ActivityFailure);
    Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
    Assert.assertEquals(
        IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());

    Assert.assertEquals(activitiesImpl.toString(), 5, activitiesImpl.invocations.size());
    Assert.assertEquals("last attempt", 5, activitiesImpl.getLastAttempt());

    testWorkflowRule.regenerateHistoryForReplay(
        WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId(),
        "laRetriesAndFails_1_xx");
  }

  /** History from 1.17 before we changed LA marker structure in 1.18 */
  @Test
  public void testSuccessfulCompletion_replay117() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "laRetriesAndFails_1_17.json", TestLocalActivityRetry.class);
  }

  public static class TestLocalActivityRetry implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setStartToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(1)
                      .setMaximumAttempts(5)
                      .setDoNotRetry(AssertionError.class.getName())
                      .build())
              .build();
      VariousTestActivities activities =
          Workflow.newLocalActivityStub(VariousTestActivities.class, options);
      activities.throwIO();

      return "ignored";
    }
  }
}
