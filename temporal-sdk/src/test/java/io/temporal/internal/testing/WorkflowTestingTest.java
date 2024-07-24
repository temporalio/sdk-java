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

package io.temporal.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Async;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowTestingTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowTestingTest.class);
  private static final String TASK_QUEUE = "test-workflow";
  private TestWorkflowEnvironment testEnvironment;

  public @Rule Timeout timeout = Timeout.seconds(10);

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
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
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
  public void testActivityScheduleToCloseTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new SimulatedTimeoutActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, options);
    try {
      workflow.execute("input1");
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      TimeoutFailure te = (TimeoutFailure) e.getCause().getCause();
      assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, te.getTimeoutType());
      assertEquals("progress1", te.getLastHeartbeatDetails().get(String.class));
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
              .getWorkflowServiceStubs()
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
            .getWorkflowServiceStubs()
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
  public void testMockedChildSimulatedTimeout() {
    String details = "timeout Details";
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(SimulatedTimeoutParentWorkflow.class);
    worker.registerWorkflowImplementationFactory(
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

  @WorkflowInterface
  public interface SignaledWorkflow {
    @WorkflowMethod
    String workflow1(String input);

    @SignalMethod
    void ProcessSignal(String input);
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
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(2)).build());

    @Override
    public String execute(String input) {
      try {
        return activity.execute(input);
      } catch (ActivityFailure e) {
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
      Activity.getExecutionContext().heartbeat("progress1");
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        // not expected
        Thread.currentThread().interrupt();
        return "interrupt";
      }
      return "done";
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
        Thread.currentThread().interrupt();
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
}
