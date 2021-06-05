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

package io.temporal.worker;

import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityWorkerOnlyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new TestActivityImpl())
          .setWorkflowTypes(ActivityWorkflowImpl.class, LocalActivityWorkflowImpl.class)
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          .build();

  @Test
  public void verifyThatLocalActivitiesAreExecuted() {
    LocalActivityWorkflow localActivityWorkflow =
        testWorkflowRule.newWorkflowStub(LocalActivityWorkflow.class);
    localActivityWorkflow.callLocalActivity();
  }

  @Test
  public void verifyThatNormalActivitiesAreTimedOut() {
    NoArgsWorkflow activityWorkflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);
    try {
      activityWorkflow.execute();
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause().getCause() instanceof TimeoutFailure);
    }
  }

  @WorkflowInterface
  public interface LocalActivityWorkflow {
    @WorkflowMethod
    void callLocalActivity();
  }

  public static class TestActivityImpl implements NoArgsActivity {
    @Override
    public void execute() {}
  }

  public static class LocalActivityWorkflowImpl implements LocalActivityWorkflow {

    @Override
    public void callLocalActivity() {
      NoArgsActivity activity =
          Workflow.newLocalActivityStub(
              NoArgsActivity.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }

  public static class ActivityWorkflowImpl implements NoArgsWorkflow {

    @Override
    public void execute() {
      NoArgsActivity activity =
          Workflow.newActivityStub(
              NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }
}
