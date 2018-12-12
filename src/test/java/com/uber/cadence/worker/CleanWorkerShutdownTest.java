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

package com.uber.cadence.worker;

import static com.uber.cadence.workflow.WorkflowTest.DOMAIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.uber.cadence.EventType;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.client.ActivityWorkerShutdownException;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CleanWorkerShutdownTest {

  private static final boolean skipDockerService =
      Boolean.parseBoolean(System.getenv("SKIP_DOCKER_SERVICE"));

  @Parameterized.Parameter public boolean useExternalService;

  @Parameterized.Parameters(name = "{1}")
  public static Object[] data() {
    if (skipDockerService) {
      return new Object[][] {{false, "TestService"}};
    } else {
      return new Object[][] {{true, "Docker"}, {false, "TestService"}};
    }
  }

  @Parameterized.Parameter(1)
  public String testType;

  @Rule public TestName testName = new TestName();

  private static IWorkflowService service;

  @Before
  public void setUp() {
    if (useExternalService) {
      service = new WorkflowServiceTChannel();
    }
  }

  @After
  public void tearDown() {
    service.close();
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
  public void testShutdown() throws ExecutionException, InterruptedException, TException {
    String taskList =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID().toString();
    WorkflowClient workflowClient;
    Worker.Factory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    if (useExternalService) {
      workerFactory = new Worker.Factory(service, DOMAIN);
      Worker worker = workerFactory.newWorker(taskList);
      workflowClient = WorkflowClient.newInstance(service, DOMAIN);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          new TestEnvironmentOptions.Builder().setDomain(DOMAIN).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.newWorkflowClient();
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
        new GetWorkflowExecutionHistoryRequest().setDomain(DOMAIN).setExecution(execution);
    GetWorkflowExecutionHistoryResponse result = service.GetWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEvents();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.ActivityTaskCompleted) {
        found = true;
        byte[] ar = e.getActivityTaskCompletedEventAttributes().getResult();
        assertEquals("\"completed\"", new String(ar, StandardCharsets.UTF_8));
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  @Test
  public void testShutdownNow() throws ExecutionException, InterruptedException, TException {
    String taskList =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID().toString();
    WorkflowClient workflowClient;
    Worker.Factory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    if (useExternalService) {
      workerFactory = new Worker.Factory(service, DOMAIN);
      Worker worker = workerFactory.newWorker(taskList);
      workflowClient = WorkflowClient.newInstance(service, DOMAIN);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          new TestEnvironmentOptions.Builder().setDomain(DOMAIN).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.newWorkflowClient();
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
        new GetWorkflowExecutionHistoryRequest().setDomain(DOMAIN).setExecution(execution);
    GetWorkflowExecutionHistoryResponse result = service.GetWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEvents();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.ActivityTaskCompleted) {
        found = true;
        byte[] ar = e.getActivityTaskCompletedEventAttributes().getResult();
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
   * Worker.Factory#shutdown()} is closed.
   */
  @Test
  public void testShutdownHeartbeatingActivity()
      throws ExecutionException, InterruptedException, TException {
    String taskList =
        "CleanWorkerShutdownTest-" + testName.getMethodName() + "-" + UUID.randomUUID().toString();
    WorkflowClient workflowClient;
    Worker.Factory workerFactory = null;
    TestWorkflowEnvironment testEnvironment = null;
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    if (useExternalService) {
      workerFactory = new Worker.Factory(service, DOMAIN);
      Worker worker = workerFactory.newWorker(taskList);
      workflowClient = WorkflowClient.newInstance(service, DOMAIN);
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      worker.registerActivitiesImplementations(new HeartbeatingActivitiesImpl(started));
      workerFactory.start();
    } else {
      TestEnvironmentOptions testOptions =
          new TestEnvironmentOptions.Builder().setDomain(DOMAIN).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      service = testEnvironment.getWorkflowService();
      Worker worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.newWorkflowClient();
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
        new GetWorkflowExecutionHistoryRequest().setDomain(DOMAIN).setExecution(execution);
    GetWorkflowExecutionHistoryResponse result = service.GetWorkflowExecutionHistory(request);
    List<HistoryEvent> events = result.getHistory().getEvents();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.ActivityTaskCompleted) {
        found = true;
        byte[] ar = e.getActivityTaskCompletedEventAttributes().getResult();
        assertEquals("\"workershutdown\"", new String(ar, StandardCharsets.UTF_8));
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }
}
