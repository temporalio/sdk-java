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

package io.temporal.activity;

import static org.junit.Assert.*;

import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityNextRetryDelayTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new NextRetryDelayActivityImpl())
          .build();

  @Test
  public void activityNextRetryDelay() {
    TestWorkflowReturnDuration workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnDuration.class);
    Duration result = workflow.execute(false);
    Assert.assertTrue(result.toMillis() > 5000 && result.toMillis() < 7000);
  }

  @Test
  public void localActivityNextRetryDelay() {
    TestWorkflowReturnDuration workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnDuration.class);
    Duration result = workflow.execute(true);
    Assert.assertTrue(result.toMillis() > 5000 && result.toMillis() < 7000);
  }

  @WorkflowInterface
  public interface TestWorkflowReturnDuration {
    @WorkflowMethod
    Duration execute(boolean useLocalActivity);
  }

  public static class TestWorkflowImpl implements TestWorkflowReturnDuration {

    private final TestActivities.NoArgsActivity activities =
        Workflow.newActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    private final TestActivities.NoArgsActivity localActivities =
        Workflow.newLocalActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newLocalActivityOptions20sScheduleToClose());

    @Override
    public Duration execute(boolean useLocalActivity) {
      long t1 = Workflow.currentTimeMillis();
      if (useLocalActivity) {
        localActivities.execute();
      } else {
        activities.execute();
      }
      long t2 = Workflow.currentTimeMillis();
      return Duration.ofMillis(t2 - t1);
    }
  }

  public static class NextRetryDelayActivityImpl implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      int attempt = Activity.getExecutionContext().getInfo().getAttempt();
      if (attempt < 4) {
        throw ApplicationFailure.newFailureWithCauseAndDelay(
            "test retry delay failure " + attempt,
            "test failure",
            null,
            Duration.ofSeconds(attempt));
      }
    }
  }
}
