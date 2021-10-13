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

package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.*;
import io.temporal.failure.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

public class ActivityCancellationTest {
  private static final AtomicBoolean timeSkipping = new AtomicBoolean();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancellationWorkflow.class)
          .setActivityImplementations(new TestCancellationActivityImpl())
          .build();

  @Test
  public void testActivityCancellation() {
    timeSkipping.set(!testWorkflowRule.isUseExternalService());
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    try {
      WorkflowClient.start(workflow::execute, "input1");
      WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
      // While activity is running time skipping is disabled.
      // So sleep for 1 second after it is scheduled.
      testWorkflowRule.sleep(Duration.ofSeconds((timeSkipping.get() ? 3600 : 0) + 1));
      untyped.cancel();
      untyped.getResult(String.class);
      fail("unreacheable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  @ActivityInterface
  public interface TestCancellationActivity {
    String activity1(String input);
  }

  private static class TestCancellationActivityImpl implements TestCancellationActivity {

    @Override
    public String activity1(String input) {
      long start = System.currentTimeMillis();
      while (true) {
        Activity.getExecutionContext().heartbeat(System.currentTimeMillis() - start);
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
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
      if (timeSkipping.get()) {
        Workflow.sleep(Duration.ofHours(1)); // test time skipping
      }
      return activity.activity1(input);
    }
  }
}
