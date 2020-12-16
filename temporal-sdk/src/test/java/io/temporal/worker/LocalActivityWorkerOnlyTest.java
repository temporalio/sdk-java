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

import static java.util.stream.Collectors.groupingBy;
import static org.junit.Assert.*;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Test;

public class LocalActivityWorkerOnlyTest {

  @ActivityInterface
  public interface TestActivity {
    void foo();
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void foo() {}
  }

  @WorkflowInterface
  public interface LocalActivityWorkflow {
    @WorkflowMethod
    void callLocalActivity();
  }

  public static class LocalActivityWorkflowImpl implements LocalActivityWorkflow {

    @Override
    public void callLocalActivity() {
      TestActivity activity =
          Workflow.newLocalActivityStub(
              TestActivity.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.foo();
    }
  }

  @WorkflowInterface
  public interface ActivityWorkflow {
    @WorkflowMethod
    void callActivity();
  }

  public static class ActivityWorkflowImpl implements ActivityWorkflow {

    @Override
    public void callActivity() {
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.foo();
    }
  }

  public static final String TASK_QUEUE = "test-workflow";

  @Test
  public void verifyThatWorkerIsNotGettingStarted() throws InterruptedException {
    String activityPollerThreadNamePrefix = "Activity Poller task";
    String workflowPollerThreadNamePrefix = "Workflow Poller task";
    String workflowHostLocalPollerThreadNamePrefix = "Host Local Workflow ";
    int hostLocalThreadCount = 22;
    int workflowPollCount = 11;
    int activityPollCount = 18;

    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setWorkflowHostLocalPollThreadCount(hostLocalThreadCount)
                    .build())
            .build();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(options);
    Worker worker =
        env.newWorker(
            TASK_QUEUE,
            WorkerOptions.newBuilder()
                .setWorkflowPollThreadCount(workflowPollCount)
                .setActivityPollThreadCount(activityPollCount)
                .setLocalActivityWorkerOnly(true)
                .build());
    // Need to register something for workers to start
    worker.registerActivitiesImplementations(new TestActivityImpl());
    worker.registerWorkflowImplementationTypes(
        LocalActivityWorkflowImpl.class, ActivityWorkflowImpl.class);
    env.start();
    Thread.sleep(1000);
    Map<String, Long> threads =
        Thread.getAllStackTraces().keySet().stream()
            .map((t) -> t.getName().substring(0, Math.min(20, t.getName().length())))
            .collect(groupingBy(Function.identity(), Collectors.counting()));
    assertEquals(hostLocalThreadCount, (long) threads.get(workflowHostLocalPollerThreadNamePrefix));
    assertEquals(workflowPollCount, (long) threads.get(workflowPollerThreadNamePrefix));
    assertFalse(threads.containsKey(activityPollerThreadNamePrefix));
    assertNull(worker.activityWorker);
  }

  @Test
  public void verifyThatLocalActivitiesAreExecuted() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder().build())
            .build();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(options);
    Worker worker =
        env.newWorker(
            TASK_QUEUE, WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build());
    // Need to register something for workers to start
    worker.registerActivitiesImplementations(new TestActivityImpl());
    worker.registerWorkflowImplementationTypes(
        LocalActivityWorkflowImpl.class, ActivityWorkflowImpl.class);
    env.start();
    WorkflowClient client = env.getWorkflowClient();
    LocalActivityWorkflow localActivityWorkflow =
        client.newWorkflowStub(
            LocalActivityWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    localActivityWorkflow.callLocalActivity();
  }

  @Test
  public void verifyThatNormalActivitiesAreTimedOut() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder().build())
            .build();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(options);
    Worker worker =
        env.newWorker(
            TASK_QUEUE, WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build());
    // Need to register something for workers to start
    worker.registerActivitiesImplementations(new TestActivityImpl());
    worker.registerWorkflowImplementationTypes(
        LocalActivityWorkflowImpl.class, ActivityWorkflowImpl.class);
    env.start();
    WorkflowClient client = env.getWorkflowClient();
    ActivityWorkflow activityWorkflow =
        client.newWorkflowStub(
            ActivityWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    try {
      activityWorkflow.callActivity();
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause().getCause() instanceof TimeoutFailure);
    }
  }
}
