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

package com.uber.cadence.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.cadence.EventType;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.ListClosedWorkflowExecutionsRequest;
import com.uber.cadence.ListClosedWorkflowExecutionsResponse;
import com.uber.cadence.ListOpenWorkflowExecutionsRequest;
import com.uber.cadence.ListOpenWorkflowExecutionsResponse;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionInfo;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.client.WorkflowTimedOutException;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.testing.SimulatedTimeoutException;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.ActivityTimeoutException;
import com.uber.cadence.workflow.Async;
import com.uber.cadence.workflow.ChildWorkflowTimedOutException;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;

public class WorkflowTestingTest {

  @Rule public Timeout globalTimeout = Timeout.seconds(5);

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  private static final String TASK_LIST = "test-workflow";

  private static TestWorkflowEnvironment testEnvironment;

  @Before
  public void setUp() {
    testEnvironment = TestWorkflowEnvironment.newInstance();
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  public interface TestWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 3600 * 24, taskList = TASK_LIST)
    String workflow1(String input);
  }

  public static class EmptyWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      return Workflow.getWorkflowInfo().getWorkflowType() + "-" + input;
    }
  }

  @Test
  public void testEmptyWorkflow() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(EmptyWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    String result = workflow.workflow1("input1");
    assertEquals("TestWorkflow::workflow1-input1", result);
  }

  public static class FailingWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      throw new IllegalThreadStateException(
          Workflow.getWorkflowInfo().getWorkflowType() + "-" + input);
    }
  }

  @Test
  public void testFailure() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(FailingWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);

    try {
      workflow.workflow1("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertEquals("TestWorkflow::workflow1-input1", e.getCause().getMessage());
    }
  }

  public interface TestActivity {

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 3600)
    String activity1(String input);
  }

  private static class ActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      return Activity.getTask().getActivityType() + "-" + input;
    }
  }

  public static class ActivityWorkflow implements TestWorkflow {

    private final TestActivity activity = Workflow.newActivityStub(TestActivity.class);

    @Override
    public String workflow1(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      return activity.activity1(input);
    }
  }

  @Test
  public void testActivity() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new ActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    String result = workflow.workflow1("input1");
    assertEquals("TestActivity::activity1-input1", result);
  }

  private static class FailingActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      throw new IllegalThreadStateException(Activity.getTask().getActivityType() + "-" + input);
    }
  }

  @Test
  public void testActivityFailure() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new FailingActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    try {
      workflow.workflow1("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertEquals("TestActivity::activity1-input1", e.getCause().getCause().getMessage());
    }
  }

  private static class SimulatedTimeoutActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      throw new SimulatedTimeoutException(TimeoutType.HEARTBEAT, "progress1");
    }
  }

  @Test
  public void testActivitySimulatedTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new SimulatedTimeoutActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    try {
      workflow.workflow1("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityTimeoutException);
      ActivityTimeoutException te = (ActivityTimeoutException) e.getCause();
      assertEquals(TimeoutType.HEARTBEAT, te.getTimeoutType());
      assertEquals("progress1", te.getDetails(String.class));
    }
  }

  public interface TestActivityTimeoutWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 3600 * 24, taskList = TASK_LIST)
    void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds);
  }

  public static class TestActivityTimeoutWorkflowImpl implements TestActivityTimeoutWorkflow {

    @Override
    public void workflow(
        long scheduleToCloseTimeoutSeconds,
        long scheduleToStartTimeoutSeconds,
        long startToCloseTimeoutSeconds) {
      ActivityOptions options =
          new ActivityOptions.Builder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(scheduleToCloseTimeoutSeconds))
              .setStartToCloseTimeout(Duration.ofSeconds(startToCloseTimeoutSeconds))
              .setScheduleToStartTimeout(Duration.ofSeconds(scheduleToStartTimeoutSeconds))
              .build();
      TestActivity activity = Workflow.newActivityStub(TestActivity.class, options);
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      activity.activity1("foo");
    }
  }

  public static class TimingOutActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      long start = System.currentTimeMillis();
      while (true) {
        Activity.heartbeat(System.currentTimeMillis() - start);
      }
    }
  }

  @Test
  public void testActivityStartToCloseTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    try {
      workflow.workflow(10, 10, 1);
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityTimeoutException);
      assertEquals(
          TimeoutType.START_TO_CLOSE, ((ActivityTimeoutException) e.getCause()).getTimeoutType());
    }
  }

  @Test
  public void testActivityScheduleToStartTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    try {
      workflow.workflow(10, 1, 10);
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityTimeoutException);
      assertEquals(
          TimeoutType.SCHEDULE_TO_START,
          ((ActivityTimeoutException) e.getCause()).getTimeoutType());
    }
  }

  @Test
  public void testActivityScheduleToCloseTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestActivityTimeoutWorkflow workflow =
        client.newWorkflowStub(TestActivityTimeoutWorkflow.class);
    try {
      workflow.workflow(1, 10, 10);
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityTimeoutException);
      assertEquals(
          TimeoutType.SCHEDULE_TO_CLOSE,
          ((ActivityTimeoutException) e.getCause()).getTimeoutType());
    }
  }

  public static class TimeoutWorkflow implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      Workflow.await(() -> false); // forever
      return "foo";
    }
  }

  @Test
  public void testWorkflowTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TimeoutWorkflow.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    WorkflowOptions options =
        new WorkflowOptions.Builder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1))
            .build();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
    try {
      workflow.workflow1("bar");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e instanceof WorkflowTimedOutException);
      assertEquals(TimeoutType.START_TO_CLOSE, ((WorkflowTimedOutException) e).getTimeoutType());
    }
  }

  public static class TimerWorkflow implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      Workflow.newTimer(Duration.ofHours(2)).get();
      return Workflow.getWorkflowInfo().getWorkflowType() + "-" + input;
    }
  }

  @Test
  public void testTimer() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TimerWorkflow.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    long start = testEnvironment.currentTimeMillis();
    String result = workflow.workflow1("input1");
    assertEquals("TestWorkflow::workflow1-input1", result);
    assertTrue(testEnvironment.currentTimeMillis() - start >= Duration.ofHours(2).toMillis());
  }

  public interface SignaledWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 3600 * 24, taskList = TASK_LIST)
    String workflow1(String input);

    @SignalMethod
    void ProcessSignal(String input);
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

  @Test
  public void testSignal() throws ExecutionException, InterruptedException {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class);
    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
    testEnvironment.sleep(Duration.ofMinutes(65)); // after 1 hour sleep in the workflow
    workflow.ProcessSignal("signalInput");
    assertEquals("signalInput-input1", result.get());
  }

  @Test
  public void testSignalWithDelayedCallback() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class);
    testEnvironment.registerDelayedCallback(
        Duration.ofMinutes(65), () -> workflow.ProcessSignal("signalInput"));
    assertEquals("signalInput-input1", workflow.workflow1("input1"));
  }

  public static class ConcurrentDecisionWorkflowImpl implements SignaledWorkflow {

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

  @Test
  public void testConcurrentDecision() throws ExecutionException, InterruptedException {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ConcurrentDecisionWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class);
    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
    workflow.ProcessSignal("signalInput");
    assertEquals("signalInput-input1", result.get());
    System.out.println(testEnvironment.getDiagnostics());
  }

  public interface TestCancellationActivity {

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 1000)
    String activity1(String input);
  }

  private static class TestCancellationActivityImpl implements TestCancellationActivity {

    @Override
    public String activity1(String input) {
      long start = System.currentTimeMillis();
      while (true) {
        Activity.heartbeat(System.currentTimeMillis() - start);
      }
    }
  }

  public static class TestCancellationWorkflow implements TestWorkflow {

    private final TestCancellationActivity activity =
        Workflow.newActivityStub(TestCancellationActivity.class);

    @Override
    public String workflow1(String input) {
      Workflow.sleep(Duration.ofHours(1)); // test time skipping
      return activity.activity1(input);
    }
  }

  @Test
  public void testActivityCancellation() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestCancellationWorkflow.class);
    worker.registerActivitiesImplementations(new TestCancellationActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    try {
      WorkflowExecution execution = WorkflowClient.start(workflow::workflow1, "input1");
      WorkflowStub untyped = client.newUntypedWorkflowStub(execution, Optional.empty());
      // While activity is running time skipping is disabled.
      // So sleep for 1 second after it is scheduled.
      testEnvironment.sleep(Duration.ofSeconds(3601));
      untyped.cancel();
      untyped.getResult(String.class);
      fail("unreacheable");
    } catch (CancellationException e) {
    }
  }

  public static class TestTimerCancellationWorkflow implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      Promise<Void> s = Async.procedure(() -> Workflow.sleep(Duration.ofDays(1)));
      TestActivity activity = Workflow.newActivityStub(TestActivity.class);
      try {
        activity.activity1("input");
        Workflow.sleep(Duration.ofDays(3));
      } catch (CancellationException e) {
        return "cancelled";
      } finally {
        s.get();
      }
      return "result";
    }
  }

  @Test
  public void testTimerCancellation() throws TException {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestTimerCancellationWorkflow.class);
    worker.registerActivitiesImplementations(new ActivityImpl());
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::workflow1, "input1");
    WorkflowStub untyped = client.newUntypedWorkflowStub(execution, Optional.empty());
    testEnvironment.sleep(Duration.ofHours(1));
    untyped.cancel();
    try {
      untyped.getResult(String.class);
      fail("unreacheable");
    } catch (CancellationException e) {
    }
    History history =
        testEnvironment
            .getWorkflowService()
            .GetWorkflowExecutionHistory(
                new GetWorkflowExecutionHistoryRequest()
                    .setExecution(execution)
                    .setDomain(client.getDomain()))
            .getHistory();
    List<HistoryEvent> historyEvents = history.getEvents();
    assertTrue(
        WorkflowExecutionUtils.prettyPrintHistory(history, false),
        WorkflowExecutionUtils.containsEvent(historyEvents, EventType.TimerCanceled));
  }

  public interface ParentWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 3600 * 24, taskList = TASK_LIST)
    String workflow(String input);

    @SignalMethod
    void signal(String value);
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {

    private String signaledValue;

    @Override
    public String workflow(String input) {
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class);
      Promise<String> result =
          Async.function(child::workflow, input, Workflow.getWorkflowInfo().getWorkflowId());
      Workflow.await(() -> signaledValue != null);
      return result.get() + signaledValue;
    }

    @Override
    public void signal(String value) {
      signaledValue = value;
    }
  }

  public interface ChildWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 3600 * 24, taskList = TASK_LIST)
    String workflow(String input, String parentId);
  }

  public static class ChildWorklfowImpl implements ChildWorkflow {

    @Override
    public String workflow(String input, String parentId) {
      Workflow.sleep(Duration.ofHours(2));
      ParentWorkflow parent = Workflow.newExternalWorkflowStub(ParentWorkflow.class, parentId);
      parent.signal(input);
      return "child ";
    }
  }

  @Test
  public void testChild() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ChildWorklfowImpl.class, ParentWorkflowImpl.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    WorkflowOptions options = new WorkflowOptions.Builder().setWorkflowId("parent1").build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    String result = workflow.workflow("input1");
    assertEquals("child input1", result);
  }

  public static class SimulatedTimeoutParentWorkflow implements ParentWorkflow {

    @Override
    public String workflow(String input) {
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class);
      Promise<String> result =
          Async.function(child::workflow, input, Workflow.getWorkflowInfo().getWorkflowId());
      return result.get();
    }

    @Override
    public void signal(String value) {}
  }

  public static class SimulatedTimeoutChildWorklfow implements ChildWorkflow {

    @Override
    public String workflow(String input, String parentId) {
      Workflow.sleep(Duration.ofHours(2));
      throw new SimulatedTimeoutException();
    }
  }

  @Test
  public void testChildSimulatedTimeout() throws Throwable {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(
        SimulatedTimeoutParentWorkflow.class, SimulatedTimeoutChildWorklfow.class);
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    WorkflowOptions options = new WorkflowOptions.Builder().setWorkflowId("parent1").build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    try {
      CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow, "input1");
      testEnvironment.sleep(Duration.ofHours(1));

      // List open workflows and validate their types
      ListOpenWorkflowExecutionsRequest listRequest =
          new ListOpenWorkflowExecutionsRequest().setDomain(testEnvironment.getDomain());
      ListOpenWorkflowExecutionsResponse listResponse =
          testEnvironment.getWorkflowService().ListOpenWorkflowExecutions(listRequest);
      List<WorkflowExecutionInfo> executions = listResponse.getExecutions();
      assertEquals(2, executions.size());
      String name0 = executions.get(0).getType().getName();
      assertTrue(
          name0,
          name0.equals("ParentWorkflow::workflow") || name0.equals("ChildWorkflow::workflow"));
      String name1 = executions.get(0).getType().getName();
      assertTrue(
          name1,
          name1.equals("ParentWorkflow::workflow") || name1.equals("ChildWorkflow::workflow"));

      try {
        result.get();
      } catch (ExecutionException e) {
        throw e.getCause();
      }
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowTimedOutException);
    }
    // List closed workflows and validate their types
    ListClosedWorkflowExecutionsRequest listRequest =
        new ListClosedWorkflowExecutionsRequest().setDomain(testEnvironment.getDomain());
    ListClosedWorkflowExecutionsResponse listResponse =
        testEnvironment.getWorkflowService().ListClosedWorkflowExecutions(listRequest);
    List<WorkflowExecutionInfo> executions = listResponse.getExecutions();
    assertEquals(2, executions.size());
    String name0 = executions.get(0).getType().getName();
    assertTrue(
        name0, name0.equals("ParentWorkflow::workflow") || name0.equals("ChildWorkflow::workflow"));
    String name1 = executions.get(0).getType().getName();
    assertTrue(
        name1, name1.equals("ParentWorkflow::workflow") || name1.equals("ChildWorkflow::workflow"));
  }

  @Test
  public void testMockedChildSimulatedTimeout() {
    Worker worker = testEnvironment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(SimulatedTimeoutParentWorkflow.class);
    worker.addWorkflowImplementationFactory(
        ChildWorkflow.class,
        () -> {
          ChildWorkflow child = mock(ChildWorkflow.class);
          when(child.workflow(anyString(), anyString())).thenThrow(new SimulatedTimeoutException());
          return child;
        });
    worker.start();
    WorkflowClient client = testEnvironment.newWorkflowClient();
    WorkflowOptions options = new WorkflowOptions.Builder().setWorkflowId("parent1").build();
    ParentWorkflow workflow = client.newWorkflowStub(ParentWorkflow.class, options);
    try {
      workflow.workflow("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowTimedOutException);
    }
  }
}
