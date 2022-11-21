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

package io.temporal.testserver.functional.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestActivities;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ActivityWithAnOutdatedTaskTokenTest {
  private static final int START_TO_CLOSE_TIMEOUT_S = 3;
  private static final int ACTIVITY_DELAY_ON_FIRST_ATTEMPT_S = START_TO_CLOSE_TIMEOUT_S + 1;
  private static final int ACTIVITY_DELAY_ON_SECOND_ATTEMPT_S = START_TO_CLOSE_TIMEOUT_S - 1;

  @Before
  public void setUp() {
    assertTrue(
        "First attempt should break statToClose",
        ACTIVITY_DELAY_ON_FIRST_ATTEMPT_S > START_TO_CLOSE_TIMEOUT_S);
    assertTrue(
        "Second attempt should fit into statToClose",
        ACTIVITY_DELAY_ON_SECOND_ATTEMPT_S < START_TO_CLOSE_TIMEOUT_S);
    assertTrue(
        "For this test to work correctly, we need the first activity to finish execution before the second one",
        ACTIVITY_DELAY_ON_FIRST_ATTEMPT_S
            < START_TO_CLOSE_TIMEOUT_S + ACTIVITY_DELAY_ON_SECOND_ATTEMPT_S);
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflow.class)
          .setActivityImplementations(new TestActivity())
          .build();

  @Test
  public void testAnActivityWithOutdatedTaskTokenCantCompleteAnExecution() {
    String result =
        testWorkflowRule.newWorkflowStub(TestWorkflows.WorkflowReturnsString.class).execute();
    assertEquals(
        "Only the second attempt should be able to complete on the server side, the first one is timed out",
        "2",
        result);
  }

  public static class TestActivity implements TestActivities.ActivityReturnsString {
    @Override
    public String execute() {
      try {
        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        switch (attempt) {
          case 1:
            Thread.sleep(TimeUnit.SECONDS.toMillis(ACTIVITY_DELAY_ON_FIRST_ATTEMPT_S));
            return "1";
          case 2:
            Thread.sleep(TimeUnit.SECONDS.toMillis(ACTIVITY_DELAY_ON_SECOND_ATTEMPT_S));
            return "2";
          default:
            throw new IllegalStateException("Unexpected attempt: " + attempt);
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static class TestWorkflow implements TestWorkflows.WorkflowReturnsString {
    @Override
    public String execute() {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(ACTIVITY_DELAY_ON_FIRST_ATTEMPT_S))
              .build();

      TestActivities.ActivityReturnsString activities =
          Workflow.newActivityStub(TestActivities.ActivityReturnsString.class, options);
      return activities.execute();
    }
  }
}
