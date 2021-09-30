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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity4;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow4;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ActivityThrowingErrorTest {

  public static final String FAILURE_TYPE = "fail";

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ActivityThrowsErrorWorkflow.class)
          .setActivityImplementations(new ApplicationFailureActivity())
          .build();

  @Test
  public void activityThrowsError() {
    String name = testName.getMethodName();
    TestWorkflow4 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow4.class);

    try {
      workflow.execute(name, true);
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals(FAILURE_TYPE, ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(3, ApplicationFailureActivity.invocations.get(name).get());
    }
  }

  @Test
  public void activityThrowsNonRetryableError() {
    String name = testName.getMethodName();
    TestWorkflow4 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow4.class);

    try {
      workflow.execute(name, false);
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals(FAILURE_TYPE, ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(1, ApplicationFailureActivity.invocations.get(name).get());
    }
  }

  public static class ActivityThrowsErrorWorkflow implements TestWorkflow4 {

    private final TestActivity4 activity =
        Workflow.newActivityStub(
            TestActivity4.class,
            ActivityOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(100))
                        .setMaximumInterval(Duration.ofMillis(100))
                        .setBackoffCoefficient(1.0)
                        .build())
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public String execute(String testName, boolean retryable) {
      activity.execute(testName, retryable);
      return testName;
    }
  }

  public static class ApplicationFailureActivity implements TestActivity4 {
    public static final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

    @Override
    public void execute(String testName, boolean retryable) {
      invocations.computeIfAbsent(testName, k -> new AtomicInteger()).incrementAndGet();
      if (retryable) {
        throw ApplicationFailure.newFailure(
            "Simulate retryable failure.", FAILURE_TYPE, FAILURE_TYPE);
      }
      throw ApplicationFailure.newNonRetryableFailure(
          "Simulate non-retryable failure.", FAILURE_TYPE, FAILURE_TYPE);
    }
  }
}
