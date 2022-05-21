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

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.testing.WorkflowTestingTest;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class ActivityTimeoutTest {
  private TestWorkflowEnvironment testEnvironment;
  private static final String TASK_QUEUE = "test-activities";

  public @Rule Timeout timeout = Timeout.seconds(10);

  @Before
  public void setUp() {
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testActivityStartToCloseTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        WorkflowTestingTest.TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new WorkflowTestingTest.TimingOutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    WorkflowTestingTest.TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestActivityTimeoutWorkflow.class, options);
    try {
      workflow.workflow(10, 10, 1, true);
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
          ((TimeoutFailure) e.getCause().getCause()).getTimeoutType());
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE,
          ((TimeoutFailure) e.getCause().getCause().getCause()).getTimeoutType());
    }
  }

  @Test
  public void testActivityScheduleToStartTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        WorkflowTestingTest.TestActivityTimeoutWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    WorkflowTestingTest.TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestActivityTimeoutWorkflow.class, options);
    try {
      workflow.workflow(10, 1, 10, true);
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START,
          ((TimeoutFailure) e.getCause().getCause()).getTimeoutType());
    }
  }

  @Test
  public void testActivityScheduleToCloseTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        WorkflowTestingTest.TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new WorkflowTestingTest.TimingOutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    WorkflowTestingTest.TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(WorkflowTestingTest.TestActivityTimeoutWorkflow.class, options);
    try {
      workflow.workflow(2, 10, 1, false);
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
          ((TimeoutFailure) e.getCause().getCause()).getTimeoutType());
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE,
          ((TimeoutFailure) e.getCause().getCause().getCause()).getTimeoutType());
    }
  }
}
