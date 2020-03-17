/*
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

import static io.temporal.workflow.WorkflowTest.DOMAIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.proto.common.HistoryEvent;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.enums.EventType;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

  @Parameterized.Parameter public boolean useExternalService;

  @Parameterized.Parameters(name = "{1}")
  public static Object[] data() {
    if (!useDockerService) {
      return new Object[][] {{false, "TestService"}};
    } else {
      return new Object[][] {{true, "Docker"}};
    }
  }

  @Parameterized.Parameter(1)
  public String testType;

  @Rule public TestName testName = new TestName();

  private static WorkflowServiceStubs service;

  @Before
  public void setUp() {
    if (useExternalService) {
      service = WorkflowServiceStubs.newInstance(WorkflowServiceStubs.LOCAL_DOCKER_TARGET);
    }
  }

  @After
  public void tearDown() {
    service.shutdownNow();
    try {
      service.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public interface TestWorkflow {
    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 100)
    String execute();
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    private final Activities activities = Workflow.newActivityStub(Activities.class);

    @Override
    public String execute() {
      return activities.execute();
    }
  }

  public interface Activities {
    @ActivityMethod(scheduleToCloseTimeoutSeconds = 100)
    String execute();
  }

  public static class ActivitiesImpl implements Activities {
    private final CompletableFuture<Boolean> started;

    public ActivitiesImpl(CompletableFuture<Boolean> started) {
      this.started = started;
    }

    @Override
    public String execute() {
      try {
        started.complete(true);
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return "interrupted";
      }
      return "completed";
    }
  }

  @Test
  public void testShutdown() throws ExecutionException, InterruptedException {
    String taskList =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID().toString();
    WorkflowClient workflowClient;
    WorkerFactory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    if (useExternalService) {
      workflowClient =
          WorkflowClient.newInstance(
              service, WorkflowClientOptions.newBuilder().setDomain(DOMAIN).build());
      workerFactory = WorkerFactory.newInstance(workflowClient);
      Worker worker = workerFactory.newWorker(taskList);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder().setDomain(DOMAIN).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.getWorkflowClient();
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      testEnvironment.start();
    }
    WorkflowOptions options = new WorkflowOptions.Builder().setTaskList(taskList).build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
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
            .setDomain(DOMAIN)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse result =
        service.blockingStub().getWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EventTypeActivityTaskCompleted) {
        found = true;
        byte[] ar = e.getActivityTaskCompletedEventAttributes().getResult().toByteArray();
        assertEquals("\"completed\"", new String(ar, StandardCharsets.UTF_8));
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  @Test
  public void testShutdownNow() throws ExecutionException, InterruptedException {
    String taskList =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID().toString();
    WorkflowClient workflowClient;
    WorkerFactory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    if (useExternalService) {
      workflowClient =
          WorkflowClient.newInstance(
              service, WorkflowClientOptions.newBuilder().setDomain(DOMAIN).build());
      workerFactory = WorkerFactory.newInstance(workflowClient);
      Worker worker = workerFactory.newWorker(taskList);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder().setDomain(DOMAIN).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.getWorkflowClient();
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      testEnvironment.start();
    }
    WorkflowOptions options = new WorkflowOptions.Builder().setTaskList(taskList).build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
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
            .setDomain(DOMAIN)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse result =
        service.blockingStub().getWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EventTypeActivityTaskCompleted) {
        found = true;
        byte[] ar = e.getActivityTaskCompletedEventAttributes().getResult().toByteArray();
        assertEquals("\"interrupted\"", new String(ar, StandardCharsets.UTF_8));
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  public static class HeartbeatingActivitiesImpl implements Activities {
    private final CompletableFuture<Boolean> started;

    public HeartbeatingActivitiesImpl(CompletableFuture<Boolean> started) {
      this.started = started;
    }

    @Override
    public String execute() {
      try {
        started.complete(true);
        Thread.sleep(1000);
        Activity.heartbeat("foo");
      } catch (ActivityWorkerShutdownException e) {
        return "workershutdown";
      } catch (InterruptedException e) {
        return "interrupted";
      }
      return "completed";
    }
  }

  /**
   * Tests that Activity#heartbeat throws ActivityWorkerShutdownException after {@link
   * WorkerFactory#shutdown()} is closed.
   */
  @Test
  public void testShutdownHeartbeatingActivity() throws ExecutionException, InterruptedException {
    String taskList =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID().toString();
    WorkflowClient workflowClient;
    WorkerFactory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    if (useExternalService) {
      workflowClient =
          WorkflowClient.newInstance(
              service, WorkflowClientOptions.newBuilder().setDomain(DOMAIN).build());
      workerFactory = WorkerFactory.newInstance(workflowClient);
      Worker worker = workerFactory.newWorker(taskList);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new HeartbeatingActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder().setDomain(DOMAIN).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.getWorkflowClient();
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new HeartbeatingActivitiesImpl(started));
      testEnvironment.start();
    }
    WorkflowOptions options = new WorkflowOptions.Builder().setTaskList(taskList).build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
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
            .setDomain(DOMAIN)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse result =
        service.blockingStub().getWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EventTypeActivityTaskCompleted) {
        found = true;
        byte[] ar = e.getActivityTaskCompletedEventAttributes().getResult().toByteArray();
        assertEquals("\"workershutdown\"", new String(ar, StandardCharsets.UTF_8));
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }
}
