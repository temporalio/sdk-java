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

import static org.junit.Assert.*;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.*;
import io.temporal.failure.*;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.testUtils.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

public class WorkflowCancellationRunningActivityTest {
  private static final AtomicBoolean timeSkipping = new AtomicBoolean();
  private static final Signal activityStarted = new Signal();
  private static final Signal activityCancelled = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancellationWorkflow.class)
          .setActivityImplementations(new TestCancellationActivityImpl())
          .build();

  @Test
  public void testActivityCancellation() throws InterruptedException {
    timeSkipping.set(!testWorkflowRule.isUseExternalService());
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = null;
    try {
      execution = WorkflowClient.start(workflow::execute, "input1");
      WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
      // While activity is running time skipping is disabled.
      // So sleep for 1 second after it is scheduled.
      if (timeSkipping.get()) {
        testWorkflowRule.sleep(Duration.ofSeconds(3600));
      }

      activityStarted.waitForSignal();

      untyped.cancel();
      untyped.getResult(String.class);
      fail("unreacheable");
    } catch (WorkflowFailedException | InterruptedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    assertTrue(activityCancelled.waitForSignal(1, TimeUnit.SECONDS));

    WorkflowExecutionHistory history = testWorkflowRule.getExecutionHistory(execution);
    assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED, history.getLastEvent().getEventType());
  }

  @ActivityInterface
  public interface TestCancellationActivity {
    String activity1(String input);
  }

  private static class TestCancellationActivityImpl implements TestCancellationActivity {

    @Override
    public String activity1(String input) {
      activityStarted.signal();
      try {
        long start = System.currentTimeMillis();
        while (true) {
          try {
            Activity.getExecutionContext().heartbeat(System.currentTimeMillis() - start);
          } catch (ActivityNotExistsException e) {
            // in case of the whole workflow gets cancelled, we are getting
            // ActivityNotExistsException
            activityCancelled.signal();
          }

          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
          }
        }
      } finally {
        activityCancelled.signal();
      }
    }
  }

  public static class TestCancellationWorkflow implements TestWorkflow1 {

    private final TestCancellationActivity activity =
        Workflow.newActivityStub(
            TestCancellationActivity.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(1000))
                .setHeartbeatTimeout(Duration.ofSeconds(1))
                .build());

    @Override
    public String execute(String input) {
      if (timeSkipping.get()) {
        Workflow.sleep(Duration.ofHours(1)); // test time skipping
      }
      return activity.activity1(input);
    }
  }
}
