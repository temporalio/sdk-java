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

package io.temporal.workflow.activityTests.cancellation;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test covers a situation when an activity is scheduled on a server side, but is not picked up
 * by a worker and getting cancelled by the workflow. Activity State Machine goes through
 *
 * <p>CREATED -> SCHEDULE_COMMAND_CREATED -> SCHEDULED_EVENT_RECORDED -> <br>
 * -> SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED -> SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED ->
 * CANCELLED
 */
public class CancellingScheduledActivityTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancellationWorkflow.class)
          // We don't register activity implementations because we don't want the activity to
          // actually being picked up in this test
          .build();

  @Test
  public void testActivityCancellationBeforeActivityIsPickedUp() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    WorkflowStub.fromTyped(workflow).start("input");
    workflow.signal();
    assertEquals("result", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String arg);

    @SignalMethod
    void signal();
  }

  @ActivityInterface
  public interface Activity {
    String activity(String input);
  }

  public static class TestCancellationWorkflow implements TestWorkflow {

    private boolean signaled = false;

    private final Activity activity =
        Workflow.newActivityStub(
            Activity.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(1000))
                .build());

    @Override
    public String execute(String input) {
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> Async.procedure(() -> activity.activity(input)));

      cancellationScope.run();

      // to force WFT finish
      Workflow.await(() -> signaled);

      cancellationScope.cancel();
      return "result";
    }

    @Override
    public void signal() {
      this.signaled = true;
    }
  }
}
