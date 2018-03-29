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

import com.uber.cadence.EventType;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.client.UntypedWorkflowStub;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowTimedOutException;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.testing.TestEnvironment;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.ActivityTimeoutException;
import com.uber.cadence.workflow.Async;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
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

  @Rule public Timeout globalTimeout = Timeout.seconds(500);

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  private static final String TASK_LIST = "test-workflow";

  private static TestEnvironment testEnvironment;

  @Before
  public void setUp() {
    testEnvironment = TestEnvironment.newInstance();
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  public interface TestWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 10, taskList = TASK_LIST)
    String workflow1(String input);
  }

  public static class EmptyWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      return Workflow.getWorkflowInfo().getWorkflowType() + "-" + input;
    }
  }

  @Test
  public void testEmptyWorkflow() {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(EmptyWorkflowImpl.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    String result = workflow.workflow1("input1");
    assertEquals("TestWorkflow::workflow1-input1", result);
  }

  public static class FailingWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow1(String input) {
      throw new IllegalThreadStateException(
          Workflow.getWorkflowInfo().getWorkflowType() + "-" + input);
    }
  }

  @Test
  public void testFailure() {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(FailingWorkflowImpl.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);

    try {
      workflow.workflow1("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertEquals("TestWorkflow::workflow1-input1", e.getCause().getMessage());
    }
  }

  public interface TestActivity {

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 1)
    String activity1(String input);
  }

  private static class ActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      return Activity.getTask().getActivityType().getName() + "-" + input;
    }
  }

  public static class ActivityWorkflow implements TestWorkflow {

    private final TestActivity activity = Workflow.newActivityStub(TestActivity.class);

    @Override
    public String workflow1(String input) {
      return activity.activity1(input);
    }
  }

  @Test
  public void testActivity() {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new ActivityImpl());
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    String result = workflow.workflow1("input1");
    assertEquals("TestActivity::activity1-input1", result);
  }

  private static class FailingActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      throw new IllegalThreadStateException(
          Activity.getTask().getActivityType().getName() + "-" + input);
    }
  }

  @Test
  public void testActivityFailure() {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new FailingActivityImpl());
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    try {
      workflow.workflow1("input1");
      fail("unreacheable");
    } catch (WorkflowException e) {
      assertEquals("TestActivity::activity1-input1", e.getCause().getCause().getMessage());
    }
  }

  public interface TestActivityTimeoutWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 1000, taskList = TASK_LIST)
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
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
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
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
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
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestActivityTimeoutWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TimingOutActivityImpl());
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
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
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TimeoutWorkflow.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
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
      Workflow.newTimer(Duration.ofSeconds(1)).get();
      return Workflow.getWorkflowInfo().getWorkflowType() + "-" + input;
    }
  }

  @Test
  public void testTimer() {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TimerWorkflow.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    long start = System.currentTimeMillis();
    String result = workflow.workflow1("input1");
    assertEquals("TestWorkflow::workflow1-input1", result);
    assertTrue(env.currentTimeMillis() - start > 1000);
  }

  public interface SignaledWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 10, taskList = TASK_LIST)
    String workflow1(String input);

    @SignalMethod
    void ProcessSignal(String input);
  }

  public static class SignaledWorkflowImpl implements SignaledWorkflow {

    private String signalInput;

    @Override
    public String workflow1(String input) {
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
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class);
    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
    workflow.ProcessSignal("signalInput");
    assertEquals("signalInput-input1", result.get());
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
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(ConcurrentDecisionWorkflowImpl.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class);
    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
    workflow.ProcessSignal("signalInput");
    assertEquals("signalInput-input1", result.get());
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
      return activity.activity1(input);
    }
  }

  @Test
  public void testActivityCancellation() throws InterruptedException {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestCancellationWorkflow.class);
    worker.registerActivitiesImplementations(new TestCancellationActivityImpl());
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    try {
      WorkflowExecution execution = WorkflowClient.start(workflow::workflow1, "input1");
      UntypedWorkflowStub untyped = client.newUntypedWorkflowStub(execution);
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
      } finally {
        s.get();
      }
      return "result";
    }
  }

  @Test
  public void testTimerCancellation() throws TException {
    TestWorkflowEnvironment env = testEnvironment.workflowEnvironment();
    Worker worker = env.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(TestTimerCancellationWorkflow.class);
    worker.start();
    WorkflowClient client = env.newWorkflowClient();
    TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::workflow1, "input1");
    UntypedWorkflowStub untyped = client.newUntypedWorkflowStub(execution);
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

  //  private static class AngryWorkflowImpl implements TestWorkflow {
  //
  //    @Override
  //    public String workflow1(String input) {
  //      throw Activity.wrap(new IOException("simulated"));
  //    }
  //  }
  //
  //  @Test
  //  public void testFailure() {
  //    TestActivityEnvironment env = testEnvironment.activityEnvironment();
  //    env.registerActivitiesImplementations(new AngryWorkflowImpl());
  //    TestWorkflow activity = env.newActivityStub(TestWorkflow.class);
  //    try {
  //      activity.workflow1("input1");
  //      fail("unreachable");
  //    } catch (ActivityFailureException e) {
  //      assertTrue(e.getMessage().contains("TestWorkflow::workflow1"));
  //      assertTrue(e.getCause() instanceof IOException);
  //      assertEquals("simulated", e.getCause().getMessage());
  //    }
  //  }
  //
  //  private static class HeartbeatWorkflowImpl implements TestWorkflow {
  //
  //    @Override
  //    public String workflow1(String input) {
  //      Activity.heartbeat("details1");
  //      return input;
  //    }
  //  }
  //
  //  @Test
  //  public void testHeartbeat() {
  //    TestActivityEnvironment env = testEnvironment.activityEnvironment();
  //    env.registerActivitiesImplementations(new HeartbeatWorkflowImpl());
  //    AtomicReference<String> details = new AtomicReference<>();
  //    env.setActivityHeartbeatListener(
  //        String.class,
  //        (d) -> {
  //          details.set(d);
  //        });
  //    TestWorkflow activity = env.newActivityStub(TestWorkflow.class);
  //    String result = activity.workflow1("input1");
  //    assertEquals("input1", result);
  //    assertEquals("details1", details.get());
  //  }
}
