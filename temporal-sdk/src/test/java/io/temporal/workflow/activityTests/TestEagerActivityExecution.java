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

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.*;

public class TestEagerActivityExecution {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  //  private TestWorkflowEnvironment env;
  private String taskQueue;

  private WorkflowClient workflowClient1;
  private WorkerFactory workerFactory1;
  private Worker worker1;

  private WorkflowClient workflowClient2;
  private WorkerFactory workerFactory2;
  private Worker worker2;

  private WorkflowClient workflowClient3;

  @Before
  public void setUp() throws Exception {
    //    TestEnvironmentOptions options =
    //        TestEnvironmentOptions.newBuilder().setUseExternalService(true).build();
    //    env = TestWorkflowEnvironment.newInstance(options);

    taskQueue = "tests-eageractivities-" + UUID.randomUUID();

    WorkflowServiceStubs wfServiceStub1 = WorkflowServiceStubs.newLocalServiceStubs();
    workflowClient1 =
        WorkflowClient.newInstance(
            wfServiceStub1,
            WorkflowClientOptions.newBuilder()
                .setIdentity("worker1")
                .setNamespace("default")
                .build());
    workerFactory1 = WorkerFactory.newInstance(workflowClient1);
    worker1 =
        workerFactory1.newWorker(
            taskQueue,
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskPollers(1)
                .setMaxConcurrentActivityTaskPollers(1)
                //                .setDisableEagerExecution(true)
                .build());
    worker1.registerActivitiesImplementations(activitiesImpl);
    worker1.registerWorkflowImplementationTypes(EagerActivityTestWorkflowImpl.class);

    WorkflowServiceStubs wfServiceStub2 = WorkflowServiceStubs.newLocalServiceStubs();
    workflowClient2 =
        WorkflowClient.newInstance(
            wfServiceStub2,
            WorkflowClientOptions.newBuilder()
                .setIdentity("worker2")
                .setNamespace("default")
                .build());
    workerFactory2 = WorkerFactory.newInstance(workflowClient2);
    worker2 =
        workerFactory2.newWorker(
            taskQueue,
            WorkerOptions.newBuilder()
                //                .setMaxConcurrentWorkflowTaskPollers(1)
                .setMaxConcurrentActivityTaskPollers(10)
                .build());
    worker2.registerActivitiesImplementations(activitiesImpl);
    //    worker2.registerWorkflowImplementationTypes(EagerActivityTestWorkflowImpl.class);

    workerFactory1.start();
    workerFactory2.start();

    //    env.start();
    WorkflowServiceStubs wfServiceStub3 = WorkflowServiceStubs.newLocalServiceStubs();
    workflowClient3 =
        WorkflowClient.newInstance(
            wfServiceStub3,
            WorkflowClientOptions.newBuilder()
                .setIdentity("client")
                .setNamespace("default")
                .build());
  }

  @After
  public void tearDown() throws Exception {
    //    env.close();

    if (this.worker1 != null) {
      this.worker1.suspendPolling();
      this.worker1 = null;
      this.workerFactory1.shutdownNow();
      this.workerFactory1 = null;
      this.workflowClient1.getWorkflowServiceStubs().shutdown();
      this.workflowClient1 = null;
    }

    if (this.worker2 != null) {
      this.worker2.suspendPolling();
      this.worker2 = null;
      this.workerFactory2.shutdownNow();
      this.workerFactory2 = null;
      this.workflowClient2.getWorkflowServiceStubs().shutdown();
      this.workflowClient2 = null;
    }

    this.workflowClient3.getWorkflowServiceStubs().shutdown();
  }

  @Test
  public void testEagerActivities() {
    EagerActivityTestWorkflow workflowStub =
        workflowClient3.newWorkflowStub(
            EagerActivityTestWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build());

    workflowStub.execute();
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();

    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setExecution(execution)
            .setNamespace("default")
            .build();
    WorkflowExecutionHistory history =
        new WorkflowExecutionHistory(
            workflowClient3
                .getWorkflowServiceStubs()
                .blockingStub()
                .getWorkflowExecutionHistory(request)
                .getHistory());

    String workflowTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(x -> x.hasWorkflowTaskStartedEventAttributes())
            .findFirst()
            .map(x -> x.getWorkflowTaskStartedEventAttributes().getIdentity())
            .get();

    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(x -> x.hasActivityTaskStartedEventAttributes())
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    //    assertEquals(workflowTaskStartedEventIdentity, activityTaskStartedEventIdentity);
    assertEquals(2, activityTaskStartedEventIdentity.size());
  }

  @Test
  public void testEagerActivitiesDisabled() {
    EagerActivityTestWorkflow workflowStub =
        workflowClient3.newWorkflowStub(
            EagerActivityTestWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build());

    workflowStub.execute();
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();

    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setExecution(execution)
            .setNamespace("default")
            .build();
    WorkflowExecutionHistory history =
        new WorkflowExecutionHistory(
            workflowClient3
                .getWorkflowServiceStubs()
                .blockingStub()
                .getWorkflowExecutionHistory(request)
                .getHistory());

    String workflowTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(x -> x.hasWorkflowTaskStartedEventAttributes())
            .findFirst()
            .map(x -> x.getWorkflowTaskStartedEventAttributes().getIdentity())
            .get();

    Set<String> activityTaskStartedEventIdentity =
        history.getEvents().stream()
            .filter(x -> x.hasActivityTaskStartedEventAttributes())
            .map(x -> x.getActivityTaskStartedEventAttributes().getIdentity())
            .collect(Collectors.toSet());

    assertNotEquals(workflowTaskStartedEventIdentity, activityTaskStartedEventIdentity);
  }

  @WorkflowInterface
  public interface EagerActivityTestWorkflow {

    @WorkflowMethod
    void execute();
  }

  public static class EagerActivityTestWorkflowImpl implements EagerActivityTestWorkflow {
    @Override
    public void execute() {
      TestActivities.VariousTestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.VariousTestActivities.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofMillis(200))
                  .build());

      ArrayList<Promise<String>> promises = new ArrayList<>();
      for (int i = 0; i < 10; i++) promises.add(Async.function(testActivities::activity));

      // Wait for promises to complete
      Promise.allOf(promises).get();
    }
  }
}
