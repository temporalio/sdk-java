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

import static io.temporal.workflow.shared.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity2;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CleanWorkerShutdownTest {

  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");
  private static WorkflowServiceStubs service;
  @Parameterized.Parameter public boolean useExternalService;

  @Parameterized.Parameter(1)
  public String testType;

  @Rule public TestName testName = new TestName();

  @Parameterized.Parameters(name = "{1}")
  public static Object[] data() {
    if (!useDockerService) {
      return new Object[][] {{false, "TestService"}};
    } else {
      return new Object[][] {{true, "Docker"}};
    }
  }

  @Before
  public void setUp() {
    if (useExternalService) {
      service =
          WorkflowServiceStubs.newInstance(
              WorkflowServiceStubsOptions.newBuilder().setTarget(serviceAddress).build());
    }
  }

  @After
  public void tearDown() {
    service.shutdownNow();
    service.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  public void testShutdown() throws ExecutionException, InterruptedException {
    String taskQueue =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID();
    WorkflowClient workflowClient;
    WorkerFactory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build();
    if (useExternalService) {
      workflowClient = WorkflowClient.newInstance(service, clientOptions);
      workerFactory = WorkerFactory.newInstance(workflowClient);
      Worker worker = workerFactory.newWorker(taskQueue);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(clientOptions).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskQueue);
      workflowClient = testEnvironment.getWorkflowClient();
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      testEnvironment.start();
    }
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
    TestWorkflowReturnString workflow =
        workflowClient.newWorkflowStub(TestWorkflowReturnString.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    started.get();
    if (useExternalService) {
      workerFactory.shutdown();
      workerFactory.awaitTermination(10, TimeUnit.MINUTES);
    } else {
      testEnvironment.shutdown();
      testEnvironment.awaitTermination(10, TimeUnit.MINUTES);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse result =
        service.blockingStub().getWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DataConverter.getDefaultInstance()
                .fromPayloads(0, Optional.of(ar), String.class, String.class);
        assertEquals("completed", r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  @Test
  public void testShutdownNow() throws ExecutionException, InterruptedException {
    String taskQueue =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID();
    WorkflowClient workflowClient;
    WorkerFactory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build();
    if (useExternalService) {
      workflowClient = WorkflowClient.newInstance(service, clientOptions);
      workerFactory = WorkerFactory.newInstance(workflowClient);
      Worker worker = workerFactory.newWorker(taskQueue);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(clientOptions).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskQueue);
      workflowClient = testEnvironment.getWorkflowClient();
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      testEnvironment.start();
    }
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
    TestWorkflowReturnString workflow =
        workflowClient.newWorkflowStub(TestWorkflowReturnString.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    started.get();
    if (useExternalService) {
      workerFactory.shutdownNow();
      workerFactory.awaitTermination(10, TimeUnit.MINUTES);
    } else {
      testEnvironment.shutdownNow();
      testEnvironment.awaitTermination(10, TimeUnit.MINUTES);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse result =
        service.blockingStub().getWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DataConverter.getDefaultInstance()
                .fromPayloads(0, Optional.of(ar), String.class, String.class);
        assertEquals(r, "interrupted", r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
    if (useExternalService) {
      service.shutdownNow();
      service.awaitTermination(10, TimeUnit.MINUTES);
    }
  }

  /**
   * Tests that Activity#heartbeat throws ActivityWorkerShutdownException after {@link
   * WorkerFactory#shutdown()} is closed.
   */
  @Test
  public void testShutdownHeartbeatingActivity() throws ExecutionException, InterruptedException {
    String taskQueue =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID();
    WorkflowClient workflowClient;
    WorkerFactory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build();
    if (useExternalService) {
      workflowClient = WorkflowClient.newInstance(service, clientOptions);
      workerFactory = WorkerFactory.newInstance(workflowClient);
      Worker worker = workerFactory.newWorker(taskQueue);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new HeartbeatingActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(clientOptions).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskQueue);
      workflowClient = testEnvironment.getWorkflowClient();
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new HeartbeatingActivitiesImpl(started));
      testEnvironment.start();
    }
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
    TestWorkflowReturnString workflow =
        workflowClient.newWorkflowStub(TestWorkflowReturnString.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    started.get();
    if (useExternalService) {
      workerFactory.shutdown();
      workerFactory.awaitTermination(10, TimeUnit.MINUTES);
    } else {
      testEnvironment.shutdown();
      testEnvironment.awaitTermination(10, TimeUnit.MINUTES);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse result =
        service.blockingStub().getWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DataConverter.getDefaultInstance()
                .fromPayloads(0, Optional.of(ar), String.class, String.class);
        assertEquals("workershutdown", r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
    if (useExternalService) {
      service.shutdownNow();
      service.awaitTermination(10, TimeUnit.MINUTES);
    }
  }

  public static class TestWorkflowImpl implements TestWorkflowReturnString {

    private final TestActivity2 activities =
        Workflow.newActivityStub(
            TestActivity2.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(100))
                .build());

    @Override
    public String execute() {
      return activities.execute();
    }
  }

  public static class ActivitiesImpl implements TestActivity2 {
    private final CompletableFuture<Boolean> started;

    public ActivitiesImpl(CompletableFuture<Boolean> started) {
      this.started = started;
    }

    @Override
    public String execute() {
      try {
        started.complete(true);
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        return "interrupted";
      }
      return "completed";
    }
  }

  public static class HeartbeatingActivitiesImpl implements TestActivity2 {
    private final CompletableFuture<Boolean> started;

    public HeartbeatingActivitiesImpl(CompletableFuture<Boolean> started) {
      this.started = started;
    }

    @Override
    public String execute() {
      try {
        started.complete(true);
        Thread.sleep(1500);
        Activity.getExecutionContext().heartbeat("foo");
      } catch (ActivityWorkerShutdownException e) {
        return "workershutdown";
      } catch (InterruptedException e) {
        return "interrupted";
      }
      return "completed";
    }
  }
}
