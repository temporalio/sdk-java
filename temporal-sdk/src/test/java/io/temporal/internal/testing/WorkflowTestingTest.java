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

package io.temporal.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow2;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class WorkflowTestingTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowTestingTest.class);
  private static final String TASK_QUEUE = "test-workflow";
  private TestWorkflowEnvironment testEnvironment;

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  @Before
  public void setUp() {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
                    .build())
            .build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testEmptyWorkflow() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EmptyWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("TestWorkflow1-input1", result);
  }

  @Test
  public void testFailure() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(FailingWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);

    try {
      workflow.execute("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertEquals(
          "message='TestWorkflow1-input1', type='test', nonRetryable=false",
          e.getCause().getMessage());
    }
  }

  @Test
  public void testActivity() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new ActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("Execute-input1", result);
  }

  /** Tests situation when an activity executes a workflow using a workflow client. */
  @Test
  public void testActivityThatExecutesWorkflow() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        TestActivityThatExecutesWorkflowImpl.class, WorkflowExecutedFromActivity.class);
    worker.registerActivitiesImplementations(
        new ActivityThatExecutesWorkflow(testEnvironment.getWorkflowClient(), TASK_QUEUE));
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    NoArgsWorkflow workflow = client.newWorkflowStub(NoArgsWorkflow.class, options);
    workflow.execute();
  }

  @Test
  public void testActivityFailure() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new FailingActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    try {
      workflow.execute("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause().getCause().getMessage().contains("message='Execute-input1'"));
      e.printStackTrace();
    }
  }

  @Test
  public void testActivitySimulatedTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new SimulatedTimeoutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    try {
      workflow.execute("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      TimeoutFailure te = (TimeoutFailure) e.getCause().getCause();
      assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, te.getTimeoutType());
      assertEquals("progress1", te.getLastHeartbeatDetails().get(String.class));
    }
  }

  @Test
  public void testActivityStartToCloseTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);
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
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);
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
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class, options);
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

  @Test
  public void testWorkflowTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TimeoutWorkflow.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    try {
      workflow.execute("bar");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e instanceof WorkflowException);
      assertEquals(
          TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE,
          ((TimeoutFailure) e.getCause()).getTimeoutType());
    }
  }

  @Test
  public void testTimer() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TimerWorkflow.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    long start = testEnvironment.currentTimeMillis();
    String result = workflow.execute("input1");
    assertEquals("TestWorkflow1-input1", result);
    assertTrue(testEnvironment.currentTimeMillis() - start >= Duration.ofHours(2).toMillis());
  }

  @Test
  public void testSignal() throws ExecutionException, InterruptedException {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class, options);
    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
    testEnvironment.sleep(Duration.ofMinutes(65)); // after 1 hour sleep in the workflow
    workflow.ProcessSignal("signalInput");
    assertEquals("signalInput-input1", result.get());
  }

  @Test
  public void testSignalWithDelayedCallback() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class, options);
    testEnvironment.registerDelayedCallback(
        Duration.ofMinutes(65), () -> workflow.ProcessSignal("signalInput"));
    assertEquals("signalInput-input1", workflow.workflow1("input1"));
  }

  @Test
  public void testConcurrentWorkflowTask() throws ExecutionException, InterruptedException {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ConcurrentWorkflowTaskWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class, options);
    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
    workflow.ProcessSignal("signalInput");
    assertEquals("signalInput-input1", result.get());
  }

  @Test(timeout = 5000)
  public void testActivityCancellation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestCancellationWorkflow.class);
    worker.registerActivitiesImplementations(new TestCancellationActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    try {
      WorkflowExecution execution = WorkflowClient.start(workflow::execute, "input1");
      WorkflowStub untyped = client.newUntypedWorkflowStub(execution, Optional.empty());
      // While activity is running time skipping is disabled.
      // So sleep for 1 second after it is scheduled.
      testEnvironment.sleep(Duration.ofSeconds(3601));
      untyped.cancel();
      untyped.getResult(String.class);
      fail("unreacheable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  @Test
  public void testTimers() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestTimersWorkflow.class);
    worker.registerActivitiesImplementations(new ActivityImpl());
    testEnvironment.start();
    long start = testEnvironment.currentTimeMillis();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    String workflowId = UUID.randomUUID().toString();
    TestWorkflow1 workflow =
        client.newWorkflowStub(
            TestWorkflow1.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowId)
                .build());
    String result = workflow.execute("input1");
    assertEquals("result", result);
    long elapsed = testEnvironment.currentTimeMillis() - start;
    assertTrue(elapsed > Duration.ofHours(3).toMillis());
  }

  @Test
  public void testChild() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ParentWorkflowImpl.class);
    worker.registerWorkflowImplementationTypes(ChildWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("parent1").build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("child input1", result);
  }

  @Test
  public void testChildSimulatedTimeout() throws Throwable {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        SimulatedTimeoutParentWorkflow.class, SimulatedTimeoutChildWorkflow.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("parent1").build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    try {
      CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow, "input1");
      testEnvironment.sleep(Duration.ofHours(1));

      // List open workflows and validate their types
      ListOpenWorkflowExecutionsRequest listRequest =
          ListOpenWorkflowExecutionsRequest.newBuilder()
              .setNamespace(testEnvironment.getNamespace())
              .build();
      ListOpenWorkflowExecutionsResponse listResponse =
          testEnvironment
              .getWorkflowService()
              .blockingStub()
              .listOpenWorkflowExecutions(listRequest);
      List<WorkflowExecutionInfo> executions = listResponse.getExecutionsList();
      assertEquals(2, executions.size());
      String name0 = executions.get(0).getType().getName();
      assertTrue(name0, name0.equals("ParentWorkflow") || name0.equals("TestWorkflow2"));
      String name1 = executions.get(0).getType().getName();
      assertTrue(name1, name1.equals("ParentWorkflow") || name1.equals("TestWorkflow2"));

      try {
        result.get();
      } catch (ExecutionException e) {
        throw e.getCause();
      }
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.getCause().getCause() instanceof TimeoutFailure);
    }
    // List closed workflows and validate their types
    ListClosedWorkflowExecutionsRequest listRequest =
        ListClosedWorkflowExecutionsRequest.newBuilder()
            .setNamespace(testEnvironment.getNamespace())
            .build();
    ListClosedWorkflowExecutionsResponse listResponse =
        testEnvironment
            .getWorkflowService()
            .blockingStub()
            .listClosedWorkflowExecutions(listRequest);
    List<WorkflowExecutionInfo> executions = listResponse.getExecutionsList();
    assertEquals(2, executions.size());
    String name0 = executions.get(0).getType().getName();
    assertTrue(name0, name0.equals("ParentWorkflow") || name0.equals("TestWorkflow2"));
    String name1 = executions.get(0).getType().getName();
    assertTrue(name1, name1.equals("ParentWorkflow") || name1.equals("TestWorkflow2"));
  }

  @Test
  @Ignore // TODO: Find a way to mock workflows as reflection doesn't work cglib generated proxies
  // mockito employs.
  public void testMockedChildSimulatedTimeout() {
    String details = "timeout Details";
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SimulatedTimeoutParentWorkflow.class);
    worker.addWorkflowImplementationFactory(
        TestWorkflow2.class,
        () -> {
          TestWorkflow2 child = mock(TestWorkflow2.class);
          when(child.execute(anyString(), anyString()))
              .thenThrow(
                  new TimeoutFailure("foo", null, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE));
          return child;
        });
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("parent1").build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    try {
      workflow.workflow("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.getCause().getCause() instanceof TimeoutFailure);
    }
  }

  @Test
  public void testWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("testing123", result);
  }

  @Test
  public void testChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        ContextPropagationParentWorkflowImpl.class, ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  @Test
  public void testThreadContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationThreadWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .setTaskQueue(TASK_QUEUE)
            .build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("asynctesting123", result);
  }

  @Test
  public void testActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(DefaultContextPropagationActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultLocalActivityContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        DefaultContextPropagationLocalActivityWorkflowImpl.class);
    worker.registerActivitiesImplementations(new ContextActivityImpl());
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("activitytesting123", result);
  }

  @Test
  public void testDefaultChildWorkflowContextPropagation() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        DefaultContextPropagationParentWorkflowImpl.class,
        ContextPropagationChildWorkflowImpl.class);
    testEnvironment.start();
    MDC.put("test", "testing123");
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
            .build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("testing123testing123", result);
  }

  @WorkflowInterface
  public interface TestActivityTimeoutWorkflow {
    @WorkflowMethod
    void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds,
        boolean disableRetries);
  }

  @WorkflowInterface
  public interface SignaledWorkflow {
    @WorkflowMethod
    String workflow1(String input);

    @SignalMethod
    void ProcessSignal(String input);
  }

  @ActivityInterface
  public interface TestCancellationActivity {
    String activity1(String input);
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    String workflow(String input);

    @SignalMethod
    void signal(String value);
  }

  public static class EmptyWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.sleep(Duration.ofMinutes(5)); // test time skipping
      return Workflow.getInfo().getWorkflowType() + "-" + input;
    }
  }

  public static class FailingWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      throw ApplicationFailure.newFailure(
          Workflow.getInfo().getWorkflowType() + "-" + input, "test");
    }
  }

  private static class ActivityImpl implements TestActivity1 {

    @Override
    public String execute(String input) {
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }

  public static class ActivityWorkflow implements TestWorkflow1 {

    private final TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

    @Override
    public String execute(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      try {
        return activity.execute(input);
      } catch (ActivityFailure e) {
        log.info("Failure", e);
        throw e;
      }
    }
  }

  public static class TestActivityThatExecutesWorkflowImpl implements NoArgsWorkflow {

    private final TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(2)).build());

    @Override
    public void execute() {
      try {
        activity.execute("foo");
      } catch (ActivityFailure e) {
        log.info("Failure", e);
        throw e;
      }
    }
  }

  private static class ActivityThatExecutesWorkflow implements TestActivity1 {

    private final WorkflowClient client;
    private final String taskQueue;

    private ActivityThatExecutesWorkflow(WorkflowClient client, String taskQueue) {
      this.client = client;
      this.taskQueue = taskQueue;
    }

    @Override
    public String execute(String input) {
      TestWorkflow1 workflow =
          client.newWorkflowStub(
              TestWorkflow1.class, WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build());
      workflow.execute(input);
      return "done";
    }
  }

  public static class WorkflowExecutedFromActivity implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      return Workflow.getInfo().getWorkflowType() + "-" + input;
    }
  }

  private static class FailingActivityImpl implements TestActivity1 {

    @Override
    public String execute(String input) {
      throw new IllegalThreadStateException(
          Activity.getExecutionContext().getInfo().getActivityType() + "-" + input);
    }
  }

  private static class SimulatedTimeoutActivityImpl implements TestActivity1 {

    @Override
    public String execute(String input) {
      throw new TimeoutFailure(
          "simulated", "progress1", TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE);
    }
  }

  public static class TestActivityTimeoutWorkflowImpl implements TestActivityTimeoutWorkflow {

    @Override
    public void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds,
        boolean disableRetries) {
      ActivityOptions.Builder options =
          ActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(scheduleToCloseTimeoutSeconds))
              .setStartToCloseTimeout(Duration.ofSeconds(startToCloseTimeoutSeconds))
              .setScheduleToStartTimeout(Duration.ofSeconds(scheduleToStartTimeoutSeconds));
      if (disableRetries) {
        options.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build());
      }
      TestActivity1 activity = Workflow.newActivityStub(TestActivity1.class, options.build());
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      activity.execute("foo");
    }
  }

  public static class TimingOutActivityImpl implements TestActivity1 {

    @Override
    public String execute(String input) {
      long start = System.currentTimeMillis();
      while (true) {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        Activity.getExecutionContext().heartbeat(System.currentTimeMillis() - start);
      }
    }
  }

  public static class TimeoutWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.await(() -> false); // forever
      return "foo";
    }
  }

  public static class TimerWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.newTimer(Duration.ofHours(2)).get();
      return Workflow.getInfo().getWorkflowType() + "-" + input;
    }
  }

  public static class SignaledWorkflowImpl implements SignaledWorkflow {

    private String signalInput;

    @Override
    public String workflow1(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      Workflow.await(() -> signalInput != null);
      return signalInput + "-" + input;
    }

    @Override
    public void ProcessSignal(String input) {
      signalInput = input;
    }
  }

  public static class ConcurrentWorkflowTaskWorkflowImpl implements SignaledWorkflow {

    private String signalInput;

    @Override
    public String workflow1(String input) {
      // Never call Thread.sleep inside a workflow.
      // Call Workflow.sleep instead.
      // Thread.sleep here to test a race condition.
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      Workflow.await(() -> signalInput != null);
      return signalInput + "-" + input;
    }

    @Override
    public void ProcessSignal(String input) {
      signalInput = input;
    }
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
          // NOOP
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
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      return activity.activity1(input);
    }
  }

  public static class TestTimersWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      long startTime = Workflow.currentTimeMillis();
      Promise<Void> s = Async.procedure(() -> Workflow.sleep(Duration.ofHours(3)));
      TestActivity1 activity =
          Workflow.newActivityStub(
              TestActivity1.class,
              ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

      activity.execute("input");
      Workflow.sleep(Duration.ofHours(1));
      s.get();
      long endTime = Workflow.currentTimeMillis();
      if (Duration.ofMillis(endTime - startTime).compareTo(Duration.ofHours(3)) < 0) {
        fail("workflow sleep interrupted unexpectedly");
      }

      return "result";
    }
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {

    private String signaledValue;

    @Override
    public String workflow(String input) {
      TestWorkflow2 child = Workflow.newChildWorkflowStub(TestWorkflow2.class);
      Promise<String> result =
          Async.function(child::execute, input, Workflow.getInfo().getWorkflowId());
      Workflow.await(() -> signaledValue != null);
      return result.get() + signaledValue;
    }

    @Override
    public void signal(String value) {
      signaledValue = value;
    }
  }

  public static class ChildWorkflowImpl implements TestWorkflow2 {

    @Override
    public String execute(String input, String parentId) {
      Workflow.sleep(Duration.ofHours(2));
      ParentWorkflow parent = Workflow.newExternalWorkflowStub(ParentWorkflow.class, parentId);
      parent.signal(input);
      return "child ";
    }
  }

  public static class SimulatedTimeoutParentWorkflow implements ParentWorkflow {

    @Override
    public String workflow(String input) {
      TestWorkflow2 child = Workflow.newChildWorkflowStub(TestWorkflow2.class);
      Promise<String> result =
          Async.function(child::execute, input, Workflow.getInfo().getWorkflowId());
      return result.get();
    }

    @Override
    public void signal(String value) {}
  }

  public static class SimulatedTimeoutChildWorkflow implements TestWorkflow2 {

    @Override
    public String execute(String input, String parentId) {
      Workflow.sleep(Duration.ofHours(2));
      throw new TimeoutFailure("simulated", null, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE);
    }
  }

  public static class TestContextPropagator implements ContextPropagator {

    @Override
    public String getName() {
      return this.getClass().getName();
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
      String testKey = (String) context;
      if (testKey != null) {
        return Collections.singletonMap(
            "test", DataConverter.getDefaultInstance().toPayload(testKey).get());
      } else {
        return Collections.emptyMap();
      }
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
      if (context.containsKey("test")) {
        return DataConverter.getDefaultInstance()
            .fromPayload(context.get("test"), String.class, String.class);

      } else {
        return null;
      }
    }

    @Override
    public Object getCurrentContext() {
      return MDC.get("test");
    }

    @Override
    public void setCurrentContext(Object context) {
      MDC.put("test", String.valueOf(context));
    }
  }

  public static class ContextPropagationWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      // The test value should be in the MDC
      return MDC.get("test");
    }
  }

  public static class ContextPropagationParentWorkflowImpl implements ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
              .build();
      TestWorkflow2 child = Workflow.newChildWorkflowStub(TestWorkflow2.class, options);

      String result = child.execute(mdcValue, Workflow.getInfo().getWorkflowId());
      return result;
    }

    @Override
    public void signal(String value) {}
  }

  public static class ContextPropagationChildWorkflowImpl implements TestWorkflow2 {

    @Override
    public String execute(String input, String parentId) {
      String mdcValue = MDC.get("test");
      return input + mdcValue;
    }
  }

  public static class ContextPropagationThreadWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String input) {
      Promise<String> asyncPromise = Async.function(this::async);
      return asyncPromise.get();
    }

    private String async() {
      return "async" + MDC.get("test");
    }
  }

  public static class ContextActivityImpl implements TestActivity1 {
    @Override
    public String execute(String input) {
      return "activity" + MDC.get("test");
    }
  }

  public static class ContextPropagationActivityWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setContextPropagators(Collections.singletonList(new TestContextPropagator()))
              .build();
      TestActivity1 activity =
          Workflow.newActivityStub(
              TestActivity1.class,
              ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

      return activity.execute("foo");
    }
  }

  public static class DefaultContextPropagationActivityWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String input) {
      ActivityOptions options =
          ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(5)).build();
      TestActivity1 activity = Workflow.newActivityStub(TestActivity1.class, options);
      return activity.execute("foo");
    }
  }

  public static class DefaultContextPropagationLocalActivityWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String input) {
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      TestActivity1 activity = Workflow.newLocalActivityStub(TestActivity1.class, options);
      return activity.execute("foo");
    }
  }

  public static class DefaultContextPropagationParentWorkflowImpl implements ParentWorkflow {

    @Override
    public String workflow(String input) {
      // Get the MDC value
      String mdcValue = MDC.get("test");

      // Fire up a child workflow
      ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder().build();
      TestWorkflow2 child = Workflow.newChildWorkflowStub(TestWorkflow2.class, options);

      String result = child.execute(mdcValue, Workflow.getInfo().getWorkflowId());
      return result;
    }

    @Override
    public void signal(String value) {}
  }
}
