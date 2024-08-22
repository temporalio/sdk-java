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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.Config;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.ExternalServiceTestConfigurator;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.*;

public class EagerActivityDispatchingTest {
  private static final String TASK_QUEUE = "test-eager-activity-dispatch";
  private TestWorkflowEnvironment env;
  private ArrayList<WorkerFactory> workerFactories;

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();
  CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier = new CountingSlotSupplier<>(100);
  CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier = new CountingSlotSupplier<>(100);
  CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(100);
  CountingSlotSupplier<NexusSlotInfo> nexusSlotSupplier = new CountingSlotSupplier<>(100);

  @Before
  public void setUp() throws Exception {
    this.env =
        TestWorkflowEnvironment.newInstance(
            ExternalServiceTestConfigurator.configuredTestEnvironmentOptions().build());
    this.workerFactories = new ArrayList<>();
  }

  @After
  public void tearDown() throws Exception {
    for (WorkerFactory workerFactory : this.workerFactories) workerFactory.shutdownNow();
    for (WorkerFactory workerFactory : this.workerFactories)
      workerFactory.awaitTermination(10, TimeUnit.SECONDS);
    this.workerFactories = null;

    env.close();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
  }

  private void setupWorker(
      String workerIdentity, WorkerOptions.Builder workerOptions, boolean registerWorkflows) {
    WorkflowClient workflowClient =
        WorkflowClient.newInstance(
            env.getWorkflowServiceStubs(),
            env.getWorkflowClient().getOptions().toBuilder().setIdentity(workerIdentity).build());
    WorkerFactory workerFactory = WorkerFactory.newInstance(workflowClient);
    workerFactories.add(workerFactory);

    workerOptions.setWorkerTuner(
        new CompositeTuner(
            workflowTaskSlotSupplier,
            activityTaskSlotSupplier,
            localActivitySlotSupplier,
            nexusSlotSupplier));
    Worker worker = workerFactory.newWorker(TASK_QUEUE, workerOptions.build());
    worker.registerActivitiesImplementations(activitiesImpl);
    if (registerWorkflows)
      worker.registerWorkflowImplementationTypes(EagerActivityTestWorkflowImpl.class);

    workerFactory.start();
  }

  @Test
  public void testEagerActivities() {
    assumeTrue(
        "Test Server doesn't support eager activity dispatch",
        SDKTestWorkflowRule.useExternalService);

    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskPollers(2)
            .setMaxConcurrentActivityTaskPollers(1)
            .setDisableEagerExecution(false),
        true);
    setupWorker(
        "worker2", WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(2), false);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(true);

    WorkflowExecutionHistory history =
        env.getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId());
    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertEquals(1, activityTaskStartedEventIdentity.size());
    assertTrue(activityTaskStartedEventIdentity.contains("worker1"));
    assertFalse(activityTaskStartedEventIdentity.contains("worker2"));
  }

  @Test
  public void testNoEagerActivitiesIfDisabledOnWorker() {
    assumeTrue(
        "Test Server doesn't support eager activity dispatch",
        SDKTestWorkflowRule.useExternalService);

    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskPollers(2)
            .setMaxConcurrentActivityTaskPollers(1)
            .setDisableEagerExecution(true),
        true);
    setupWorker(
        "worker2", WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(2), false);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(true);

    WorkflowExecutionHistory history =
        env.getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId());
    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertEquals(2, activityTaskStartedEventIdentity.size());
    assertTrue(activityTaskStartedEventIdentity.contains("worker1"));
    assertTrue(activityTaskStartedEventIdentity.contains("worker2"));
  }

  @Test
  public void testNoEagerActivitiesIfDisabledOnActivity() {
    assumeTrue(
        "Test Server doesn't support eager activity dispatch",
        SDKTestWorkflowRule.useExternalService);

    setupWorker(
        "worker1",
        WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskPollers(2)
            .setMaxConcurrentActivityTaskPollers(1)
            .setDisableEagerExecution(false),
        true);
    setupWorker(
        "worker2", WorkerOptions.newBuilder().setMaxConcurrentActivityTaskPollers(2), false);

    EagerActivityTestWorkflow workflowStub =
        env.getWorkflowClient()
            .newWorkflowStub(
                EagerActivityTestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    workflowStub.execute(false);

    WorkflowExecutionHistory history =
        env.getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId());
    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertEquals(2, activityTaskStartedEventIdentity.size());
    assertTrue(activityTaskStartedEventIdentity.contains("worker1"));
    assertTrue(activityTaskStartedEventIdentity.contains("worker2"));
  }

  @WorkflowInterface
  public interface EagerActivityTestWorkflow {
    @WorkflowMethod
    void execute(boolean enableEagerActivityDispatch);
  }

  public static class EagerActivityTestWorkflowImpl implements EagerActivityTestWorkflow {
    @Override
    public void execute(boolean enableEagerActivityDispatch) {
      TestActivities.VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofMillis(200))
                  .setDisableEagerExecution(!enableEagerActivityDispatch)
                  .build());

      ArrayList<Promise<String>> promises = new ArrayList<>();
      for (int i = 0; i < Config.EAGER_ACTIVITIES_LIMIT; i++)
        promises.add(Async.function(testActivities::activity));
      Promise.allOf(promises).get();
    }
  }
}
