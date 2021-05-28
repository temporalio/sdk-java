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
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import java.time.Duration;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ActivityThrowingErrorTest {

  private static final String TASK_QUEUE = "test-workflow";

  private TestWorkflowEnvironment testEnvironment;

  @Before
  public void setUp() {
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class LocalActivityThrowsErrorWorkflow implements TestWorkflow {

    private final TestActivity1 activity1 =
        Workflow.newLocalActivityStub(
            TestActivity1.class,
            LocalActivityOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofMinutes(2))
                        .build())
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public String workflow(String input) {
      return activity1.execute(input);
    }
  }

  public static class ActivityThrowsErrorWorkflow implements TestWorkflow {

    private final TestActivity1 activity1 =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofMinutes(2))
                        .build())
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public String workflow(String input) {
      return activity1.execute(input);
    }
  }

  private static class Activity1Impl implements TestActivity1 {
    @Override
    public String execute(String input) {
      return Workflow.randomUUID().toString();
    }
  }

  @Test
  public void localActivityThrowsError() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(LocalActivityThrowsErrorWorkflow.class);
    worker.registerActivitiesImplementations(new Activity1Impl());

    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(1)
                    .setInitialInterval(Duration.ofMinutes(2))
                    .build())
            .build();

    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
    try {
      workflow.workflow(UUID.randomUUID().toString());
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("java.lang.Error", ((ApplicationFailure) e.getCause().getCause()).getType());
    }
  }

  @Test
  public void activityThrowsError() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ActivityThrowsErrorWorkflow.class);
    worker.registerActivitiesImplementations(new Activity1Impl());

    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(1)
                    .setInitialInterval(Duration.ofMinutes(2))
                    .build())
            .build();

    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
    try {
      workflow.workflow(UUID.randomUUID().toString());
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("java.lang.Error", ((ApplicationFailure) e.getCause().getCause()).getType());
    }
  }
}
