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
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Covers behavior and observable effects of an activity when the initiating workflow gets cancelled
 */
@RunWith(value = Parameterized.class)
public class WorkflowClosedRunningActivityTest {
  private final Signal activityStarted = new Signal();
  private final Signal activityCancelled = new Signal();

  @Parameterized.Parameters(name = "{0}")
  public static EventType[] closureTypes() {
    return new EventType[] {
      EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
      EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED,
      EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT
    };
  }

  @Parameterized.Parameter(0)
  public EventType eventType;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancellationWorkflow.class)
          .setActivityImplementations(new TestCancellationActivityImpl())
          .build();

  @After
  public void tearDown() throws Exception {
    activityStarted.clearSignal();
    activityCancelled.clearSignal();
  }

  @Test
  public void activitySeesActivityNotExistException() throws InterruptedException {
    TestWorkflow1 workflow =
        eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT
            ? testWorkflowRule
                .getWorkflowClient()
                .newWorkflowStub(
                    TestWorkflow1.class,
                    WorkflowOptions.newBuilder()
                        .setTaskQueue(testWorkflowRule.getTaskQueue())
                        .setWorkflowRunTimeout(Duration.of(1, ChronoUnit.SECONDS))
                        .validateBuildWithDefaults())
            : testWorkflowRule.newWorkflowStub(TestWorkflow1.class);

    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "input1");
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);

    activityStarted.waitForSignal();

    switch (eventType) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        untyped.cancel();
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        untyped.terminate("test termination");
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        assertTrue(activityCancelled.waitForSignal(7, TimeUnit.SECONDS));
        break;
    }

    assertTrue(activityCancelled.waitForSignal(1, TimeUnit.SECONDS));

    WorkflowExecutionHistory history = testWorkflowRule.getExecutionHistory(execution);
    assertEquals(eventType, history.getLastEvent().getEventType());
  }

  @ActivityInterface
  public interface TestCancellationActivity {
    String activity1(String input);
  }

  private class TestCancellationActivityImpl implements TestCancellationActivity {

    @Override
    public String activity1(String input) {
      activityStarted.signal();
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
      return activity.activity1(input);
    }
  }
}
