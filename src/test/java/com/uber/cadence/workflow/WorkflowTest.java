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

package com.uber.cadence.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.googlecode.junittoolbox.ParallelParameterized;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.activity.MethodRetry;
import com.uber.cadence.client.ActivityCancelledException;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.ActivityNotExistsException;
import com.uber.cadence.client.DuplicateWorkflowException;
import com.uber.cadence.client.UntypedWorkflowStub;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowException;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.sync.DeterministicRunnerTest;
import com.uber.cadence.testing.TestEnvironment;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(ParallelParameterized.class)
public class WorkflowTest {

  public static final String ANNOTATION_TASK_LIST = "WorkflowTest-testExecute[Docker]";

  @Parameters(name = "{1}")
  public static Object[] data() {
    return new Object[][] {{true, "Docker"}, {false, "TestService"}};
  }

  @Rule public TestName testName = new TestName();
  @Rule public Timeout globalTimeout = Timeout.seconds(500);

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnvironment != null) {
            System.err.println(testEnvironment.getDiagnostics());
          }
        }
      };

  @Parameter public boolean useExternalService;

  @Parameter(1)
  public String testType;

  private static final String domain = "UnitTest";
  private static final Logger log = LoggerFactory.getLogger(WorkflowTest.class);

  private String taskList;

  private Worker worker;
  private Worker workerAnnotatedTaskList;
  private TestActivitiesImpl activitiesImpl;
  private WorkflowClient workflowClient;
  private WorkflowClient workflowClientWithOptions;
  private TestEnvironment testEnvironment;
  private TestWorkflowEnvironment workflowEnvironment;
  private ScheduledExecutorService scheduledExecutor;
  private List<ScheduledFuture<?>> delayedCallbacks = new ArrayList<>();

  private static WorkflowOptions.Builder newWorkflowOptionsBuilder(String taskList) {
    return new WorkflowOptions.Builder()
        .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
        .setTaskList(taskList);
  }

  private static ActivityOptions newActivityOptions1(String taskList) {
    return new ActivityOptions.Builder()
        .setTaskList(taskList)
        .setScheduleToCloseTimeout(Duration.ofSeconds(5))
        .setHeartbeatTimeout(Duration.ofSeconds(5))
        .setScheduleToStartTimeout(Duration.ofSeconds(5))
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .build();
  }

  private static ActivityOptions newActivityOptions2() {
    return new ActivityOptions.Builder().setScheduleToCloseTimeout(Duration.ofSeconds(20)).build();
  }

  @Before
  public void setUp() {
    if (testName.getMethodName().equals("testExecute[TestService]")
        || testName.getMethodName().equals("testStart[TestService]")) {
      taskList = ANNOTATION_TASK_LIST;
    } else {
      taskList = "WorkflowTest-" + testName.getMethodName();
    }
    if (useExternalService) {
      worker = new Worker(domain, taskList);
      workflowClient = WorkflowClient.newInstance(domain);
      WorkflowClientOptions clientOptions =
          new WorkflowClientOptions.Builder()
              .setDataConverter(JsonDataConverter.getInstance())
              .build();
      workflowClientWithOptions = WorkflowClient.newInstance(domain, clientOptions);
      scheduledExecutor = new ScheduledThreadPoolExecutor(1);
    } else {
      testEnvironment =
          TestEnvironment.newInstance(
              new TestEnvironmentOptions.Builder().setDomain(domain).build());
      workflowEnvironment = testEnvironment.workflowEnvironment();
      worker = workflowEnvironment.newWorker(taskList);
      workflowClient = workflowEnvironment.newWorkflowClient();
      workflowClientWithOptions = workflowEnvironment.newWorkflowClient();
    }

    ActivityCompletionClient completionClient = workflowClient.newActivityCompletionClient();
    activitiesImpl = new TestActivitiesImpl(completionClient);
    worker.registerActivitiesImplementations(activitiesImpl);

    newWorkflowOptionsBuilder(taskList);

    newActivityOptions1(taskList);
    activitiesImpl.invocations.clear();
    activitiesImpl.procResult.clear();
  }

  @After
  public void tearDown() throws Throwable {
    worker.shutdown(Duration.ofMillis(1));
    activitiesImpl.close();
    if (testEnvironment != null) {
      testEnvironment.close();
    }
    for (ScheduledFuture<?> result : delayedCallbacks) {
      if (result.isDone() && !result.isCancelled()) {
        try {
          result.get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
          throw e.getCause();
        }
      }
    }
  }

  private void startWorkerFor(Class<?>... workflowTypes) {
    worker.registerWorkflowImplementationTypes(workflowTypes);
    worker.start();
  }

  void registerDelayedCallback(Duration delay, Runnable r) {
    if (useExternalService) {
      ScheduledFuture<?> result =
          scheduledExecutor.schedule(r, delay.toMillis(), TimeUnit.MILLISECONDS);
      delayedCallbacks.add(result);
    } else {
      workflowEnvironment.registerDelayedCallback(delay, r);
    }
  }

  public interface TestWorkflow1 {

    @WorkflowMethod
    String execute(String taskList);
  }

  public interface TestWorkflowSignaled {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal1(String arg);
  }

  public interface TestWorkflow2 {

    @WorkflowMethod(name = "testActivity")
    String execute(boolean useExternalService);
  }

  public static class TestSyncWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities activities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));
      // Invoke synchronously in a separate thread for testing purposes only.
      // In real workflows use
      // Async.procedure(activities::activityWithDelay, 1000, true)
      Promise<String> a1 = Async.function(() -> activities.activityWithDelay(1000, true));
      Workflow.sleep(2000);
      return activities.activity2(a1.get(), 10);
    }
  }

  @Test
  public void testSync() {
    startWorkerFor(TestSyncWorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("activity10", result);
  }

  public static class TestActivityRetry implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ActivityOptions options =
          new ActivityOptions.Builder()
              .setTaskList(taskList)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setScheduleToStartTimeout(Duration.ofSeconds(5))
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setRetryOptions(
                  new RetryOptions.Builder()
                      .setMinimumAttempts(2)
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      TestActivities activities = Workflow.newActivityStub(TestActivities.class, options);
      activities.throwIO();
      return "ignored";
    }
  }

  @Test
  public void testActivityRetry() {
    startWorkerFor(TestActivityRetry.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    try {
      workflowStub.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause().getCause() instanceof IOException);
    }
    assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
  }

  public static class TestActivityRetryAnnotated implements TestWorkflow1 {

    private final TestActivities activities;

    public TestActivityRetryAnnotated() {
      this.activities = Workflow.newActivityStub(TestActivities.class);
    }

    @Override
    public String execute(String taskList) {
      activities.throwIOAnnotated();
      return "ignored";
    }
  }

  @Test
  public void testActivityRetryAnnotated() {
    startWorkerFor(TestActivityRetryAnnotated.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    try {
      workflowStub.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause().getCause() instanceof IOException);
    }
    assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
  }

  public static class TestAsyncActivityRetry implements TestWorkflow1 {

    private TestActivities activities;

    @Override
    public String execute(String taskList) {
      ActivityOptions options =
          new ActivityOptions.Builder()
              .setTaskList(taskList)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setScheduleToStartTimeout(Duration.ofSeconds(5))
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setRetryOptions(
                  new RetryOptions.Builder()
                      .setMinimumAttempts(2)
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      this.activities = Workflow.newActivityStub(TestActivities.class, options);
      Async.procedure(activities::throwIO).get();
      return "ignored";
    }
  }

  @Test
  public void testAsyncActivityRetry() {
    startWorkerFor(TestAsyncActivityRetry.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    try {
      workflowStub.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause().getCause() instanceof IOException);
    }
    assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
  }

  public static class TestHeartbeatTimeoutDetails implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ActivityOptions options =
          new ActivityOptions.Builder()
              .setTaskList(taskList)
              .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout;
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      TestActivities activities = Workflow.newActivityStub(TestActivities.class, options);
      try {
        // false for second argument means to heartbeat once to set details and then stop.
        activities.activityWithDelay(5000, false);
      } catch (ActivityTimeoutException e) {
        assertEquals(TimeoutType.HEARTBEAT, e.getTimeoutType());
        return e.getDetails(String.class);
      }
      throw new RuntimeException("unreachable");
    }
  }

  @Test
  public void testHeartbeatTimeoutDetails() {
    startWorkerFor(TestHeartbeatTimeoutDetails.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("heartbeatValue", result);
  }

  @Test
  public void testSyncUntypedAndStackTrace() throws InterruptedException {
    startWorkerFor(TestSyncWorkflowImpl.class);
    UntypedWorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1::execute", newWorkflowOptionsBuilder(taskList).build());
    WorkflowExecution execution = workflowStub.start();
    Thread.sleep(500);
    String stackTrace = workflowStub.query(WorkflowClient.QUERY_TYPE_STACK_TRCE, String.class);
    assertTrue(stackTrace, stackTrace.contains("WorkflowTest$TestSyncWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
    // Test stub created from workflow execution.
    workflowStub = workflowClient.newUntypedWorkflowStub(execution, workflowStub.getWorkflowType());
    stackTrace = workflowStub.query(WorkflowClient.QUERY_TYPE_STACK_TRCE, String.class);
    assertTrue(stackTrace, stackTrace.contains("WorkflowTest$TestSyncWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
    String result = workflowStub.getResult(String.class);
    assertEquals("activity10", result);
  }

  @Test
  public void testWorkflowCancellation() {
    startWorkerFor(TestSyncWorkflowImpl.class);
    UntypedWorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1::execute", newWorkflowOptionsBuilder(taskList).build());
    client.start();
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (CancellationException ignored) {
    }
  }

  public static class TestCancellationScopePromise implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      Promise<String> cancellationRequest = CancellationScope.current().getCancellationRequest();
      cancellationRequest.get();
      return "done";
    }
  }

  @Test
  public void testWorkflowCancellationScopePromise() {
    startWorkerFor(TestCancellationScopePromise.class);
    UntypedWorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1::execute", newWorkflowOptionsBuilder(taskList).build());
    client.start(taskList);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (CancellationException ignored) {
    }
  }

  public static class TestDetachedCancellationScope implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));
      try {
        testActivities.activityWithDelay(100000, true);
        fail("unreachable");
      } catch (CancellationException e) {
        Workflow.newDetachedCancellationScope(
            () -> assertEquals("a1", testActivities.activity1("a1")));
      }
      try {
        Workflow.sleep(Duration.ofHours(1));
        fail("unreachable");
      } catch (CancellationException e) {
        Workflow.newDetachedCancellationScope(
            () -> assertEquals("a12", testActivities.activity2("a1", 2)));
      }
      try {
        Workflow.newTimer(Duration.ofHours(1)).get();
        fail("unreachable");
      } catch (CancellationException e) {
        Workflow.newDetachedCancellationScope(
            () -> assertEquals("a123", testActivities.activity3("a1", 2, 3)));
      }
      return "result";
    }
  }

  @Test
  public void testDetachedScope() throws InterruptedException {
    startWorkerFor(TestDetachedCancellationScope.class);
    UntypedWorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1::execute", newWorkflowOptionsBuilder(taskList).build());
    client.start();
    Thread.sleep(500); // To let activityWithDelay start.
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (CancellationException ignored) {
    }
    activitiesImpl.assertInvocations("activityWithDelay", "activity1", "activity2", "activity3");
  }

  public interface TestContinueAsNew {

    @WorkflowMethod
    int execute(int count);
  }

  public static class TestContinueAsNewImpl implements TestContinueAsNew {

    @Override
    public int execute(int count) {
      if (count == 0) {
        return 111;
      }
      TestContinueAsNew next = Workflow.newContinueAsNewStub(TestContinueAsNew.class, null);
      next.execute(count - 1);
      throw new RuntimeException("unreachable");
    }
  }

  @Test
  public void testContinueAsNew() {
    startWorkerFor(TestContinueAsNewImpl.class);
    TestContinueAsNew client =
        workflowClient.newWorkflowStub(
            TestContinueAsNew.class, newWorkflowOptionsBuilder(taskList).build());
    int result = client.execute(4);
    assertEquals(111, result);
  }

  public static class TestAsyncActivityWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions2());
      Promise<String> a = Async.function(testActivities::activity);
      Promise<String> a1 = Async.function(testActivities::activity1, "1");
      Promise<String> a2 = Async.function(testActivities::activity2, "1", 2);
      Promise<String> a3 = Async.function(testActivities::activity3, "1", 2, 3);
      Promise<String> a4 = Async.function(testActivities::activity4, "1", 2, 3, 4);
      Promise<String> a5 = Async.function(testActivities::activity5, "1", 2, 3, 4, 5);
      Promise<String> a6 = Async.function(testActivities::activity6, "1", 2, 3, 4, 5, 6);
      assertEquals("activity", a.get());
      assertEquals("1", a1.get());
      assertEquals("12", a2.get());
      assertEquals("123", a3.get());
      assertEquals("1234", a4.get());
      assertEquals("12345", a5.get());
      assertEquals("123456", a6.get());

      Async.procedure(testActivities::proc).get();
      Async.procedure(testActivities::proc1, "1").get();
      Async.procedure(testActivities::proc2, "1", 2).get();
      Async.procedure(testActivities::proc3, "1", 2, 3).get();
      Async.procedure(testActivities::proc4, "1", 2, 3, 4).get();
      Async.procedure(testActivities::proc5, "1", 2, 3, 4, 5).get();
      Async.procedure(testActivities::proc6, "1", 2, 3, 4, 5, 6).get();
      return "workflow";
    }
  }

  @Test
  public void testAsyncActivity() {
    startWorkerFor(TestAsyncActivityWorkflowImpl.class);
    TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = client.execute(taskList);
    assertEquals("workflow", result);
    assertEquals("proc", activitiesImpl.procResult.get(0));
    assertEquals("1", activitiesImpl.procResult.get(1));
    assertEquals("12", activitiesImpl.procResult.get(2));
    assertEquals("123", activitiesImpl.procResult.get(3));
    assertEquals("1234", activitiesImpl.procResult.get(4));
    assertEquals("12345", activitiesImpl.procResult.get(5));
    assertEquals("123456", activitiesImpl.procResult.get(6));
  }

  private void assertResult(String expected, WorkflowExecution execution) {
    String result =
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(String.class);
    assertEquals(expected, result);
  }

  private void waitForProc(WorkflowExecution execution) {
    workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(Void.class);
  }

  @Test
  public void testStart() {
    startWorkerFor(TestMultiargsWorkflowsImpl.class);
    WorkflowOptions workflowOptions = newWorkflowOptionsBuilder(taskList).build();
    TestMultiargsWorkflowsFunc stubF =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc.class, workflowOptions);
    assertResult("func", WorkflowClient.start(stubF::func));
    assertEquals("func", stubF.func()); // Check that duplicated start just returns the result.
    TestMultiargsWorkflowsFunc1 stubF1 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc1.class);

    if (!useExternalService) {
      // Use worker that polls on a task list configured through @WorkflowMethod annotation of func1
      assertResult("1", WorkflowClient.start(stubF1::func1, "1"));
      assertEquals("1", stubF1.func1("1")); // Check that duplicated start just returns the result.
    }
    // Check that duplicated start is not allowed for AllowDuplicate IdReusePolicy
    TestMultiargsWorkflowsFunc2 stubF2 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsFunc2.class,
            newWorkflowOptionsBuilder(taskList)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.AllowDuplicate)
                .build());
    assertResult("12", WorkflowClient.start(stubF2::func2, "1", 2));
    try {
      stubF2.func2("1", 2);
      fail("unreachable");
    } catch (DuplicateWorkflowException e) {
      // expected
    }
    TestMultiargsWorkflowsFunc3 stubF3 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc3.class, workflowOptions);
    assertResult("123", WorkflowClient.start(stubF3::func3, "1", 2, 3));
    TestMultiargsWorkflowsFunc4 stubF4 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc4.class, workflowOptions);
    assertResult("1234", WorkflowClient.start(stubF4::func4, "1", 2, 3, 4));
    TestMultiargsWorkflowsFunc5 stubF5 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc5.class, workflowOptions);
    assertResult("12345", WorkflowClient.start(stubF5::func5, "1", 2, 3, 4, 5));
    TestMultiargsWorkflowsFunc6 stubF6 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc6.class, workflowOptions);
    assertResult("123456", WorkflowClient.start(stubF6::func6, "1", 2, 3, 4, 5, 6));

    TestMultiargsWorkflowsProc stubP =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP::proc));
    TestMultiargsWorkflowsProc1 stubP1 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc1.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP1::proc1, "1"));
    TestMultiargsWorkflowsProc2 stubP2 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc2.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP2::proc2, "1", 2));
    TestMultiargsWorkflowsProc3 stubP3 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc3.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP3::proc3, "1", 2, 3));
    TestMultiargsWorkflowsProc4 stubP4 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc4.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP4::proc4, "1", 2, 3, 4));
    TestMultiargsWorkflowsProc5 stubP5 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc5.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP5::proc5, "1", 2, 3, 4, 5));
    TestMultiargsWorkflowsProc6 stubP6 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc6.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP6::proc6, "1", 2, 3, 4, 5, 6));

    assertEquals("proc", TestMultiargsWorkflowsImpl.procResult.get(0));
    assertEquals("1", TestMultiargsWorkflowsImpl.procResult.get(1));
    assertEquals("12", TestMultiargsWorkflowsImpl.procResult.get(2));
    assertEquals("123", TestMultiargsWorkflowsImpl.procResult.get(3));
    assertEquals("1234", TestMultiargsWorkflowsImpl.procResult.get(4));
    assertEquals("12345", TestMultiargsWorkflowsImpl.procResult.get(5));
    assertEquals("123456", TestMultiargsWorkflowsImpl.procResult.get(6));
  }

  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    TestMultiargsWorkflowsImpl.procResult.clear();
    startWorkerFor(TestMultiargsWorkflowsImpl.class);
    WorkflowOptions workflowOptions =
        newWorkflowOptionsBuilder(taskList)
            .setTaskList(ANNOTATION_TASK_LIST) // To override func2 annotation property
            .build();
    TestMultiargsWorkflowsFunc stubF =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc.class, workflowOptions);
    assertEquals("func", WorkflowClient.execute(stubF::func).get());
    TestMultiargsWorkflowsFunc1 stubF1 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc1.class, workflowOptions);
    assertEquals("1", WorkflowClient.execute(stubF1::func1, "1").get());
    assertEquals("1", stubF1.func1("1")); // Check that duplicated start just returns the result.
    TestMultiargsWorkflowsFunc2 stubF2 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc2.class, workflowOptions);
    assertEquals("12", WorkflowClient.execute(stubF2::func2, "1", 2).get());
    TestMultiargsWorkflowsFunc3 stubF3 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc3.class, workflowOptions);
    assertEquals("123", WorkflowClient.execute(stubF3::func3, "1", 2, 3).get());
    TestMultiargsWorkflowsFunc4 stubF4 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc4.class, workflowOptions);
    assertEquals("1234", WorkflowClient.execute(stubF4::func4, "1", 2, 3, 4).get());
    TestMultiargsWorkflowsFunc5 stubF5 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc5.class, workflowOptions);
    assertEquals("12345", WorkflowClient.execute(stubF5::func5, "1", 2, 3, 4, 5).get());
    TestMultiargsWorkflowsFunc6 stubF6 =
        workflowClient.newWorkflowStub(TestMultiargsWorkflowsFunc6.class, workflowOptions);
    assertEquals("123456", WorkflowClient.execute(stubF6::func6, "1", 2, 3, 4, 5, 6).get());

    TestMultiargsWorkflowsProc stubP =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc.class, workflowOptions);
    WorkflowClient.execute(stubP::proc).get();
    TestMultiargsWorkflowsProc1 stubP1 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc1.class, workflowOptions);
    WorkflowClient.execute(stubP1::proc1, "1").get();
    TestMultiargsWorkflowsProc2 stubP2 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc2.class, workflowOptions);
    WorkflowClient.execute(stubP2::proc2, "1", 2).get();
    TestMultiargsWorkflowsProc3 stubP3 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc3.class, workflowOptions);
    WorkflowClient.execute(stubP3::proc3, "1", 2, 3).get();
    TestMultiargsWorkflowsProc4 stubP4 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc4.class, workflowOptions);
    WorkflowClient.execute(stubP4::proc4, "1", 2, 3, 4).get();
    TestMultiargsWorkflowsProc5 stubP5 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc5.class, workflowOptions);
    WorkflowClient.execute(stubP5::proc5, "1", 2, 3, 4, 5).get();
    TestMultiargsWorkflowsProc6 stubP6 =
        workflowClientWithOptions.newWorkflowStub(
            TestMultiargsWorkflowsProc6.class, workflowOptions);
    WorkflowClient.execute(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();

    assertEquals("proc", TestMultiargsWorkflowsImpl.procResult.get(0));
    assertEquals("1", TestMultiargsWorkflowsImpl.procResult.get(1));
    assertEquals("12", TestMultiargsWorkflowsImpl.procResult.get(2));
    assertEquals("123", TestMultiargsWorkflowsImpl.procResult.get(3));
    assertEquals("1234", TestMultiargsWorkflowsImpl.procResult.get(4));
    assertEquals("12345", TestMultiargsWorkflowsImpl.procResult.get(5));
    assertEquals("123456", TestMultiargsWorkflowsImpl.procResult.get(6));
  }

  public static class TestTimerWorkflowImpl implements TestWorkflow2 {

    @Override
    public String execute(boolean useExternalService) {
      Promise<Void> timer1;
      Promise<Void> timer2;
      if (useExternalService) {
        timer1 = Workflow.newTimer(Duration.ofMillis(700));
        timer2 = Workflow.newTimer(Duration.ofMillis(1300));
      } else {
        timer1 = Workflow.newTimer(Duration.ofSeconds(700));
        timer2 = Workflow.newTimer(Duration.ofSeconds(1300));
      }
      long time = Workflow.currentTimeMillis();
      timer1
          .thenApply(
              (r) -> {
                // Testing that timer can be created from a callback thread.
                if (useExternalService) {
                  Workflow.newTimer(Duration.ofSeconds(10));
                } else {
                  Workflow.newTimer(Duration.ofHours(10));
                }
                Workflow.currentTimeMillis(); // Testing that time is available here.
                return r;
              })
          .get();
      timer1.get();
      long slept = Workflow.currentTimeMillis() - time;
      // Also checks that rounding up to a second works.
      assertTrue(String.valueOf(slept), slept > 1000);
      timer2.get();
      slept = Workflow.currentTimeMillis() - time;
      assertTrue(String.valueOf(slept), slept > 2000);
      return "testTimer";
    }
  }

  @Test
  public void testTimer() {
    startWorkerFor(TestTimerWorkflowImpl.class);
    WorkflowOptions options;
    if (useExternalService) {
      options = newWorkflowOptionsBuilder(taskList).build();
    } else {
      options =
          newWorkflowOptionsBuilder(taskList)
              .setExecutionStartToCloseTimeout(Duration.ofDays(1))
              .build();
    }
    TestWorkflow2 client = workflowClient.newWorkflowStub(TestWorkflow2.class, options);
    String result = client.execute(useExternalService);
    assertEquals("testTimer", result);
  }

  private static final RetryOptions retryOptions =
      new RetryOptions.Builder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(1))
          .setExpiration(Duration.ofSeconds(2))
          .setBackoffCoefficient(1)
          .build();

  public static class TestAsyncRetryWorkflowImpl implements TestWorkflow2 {

    static List<String> trace = new ArrayList<>();

    @Override
    public String execute(boolean useExternalService) {
      trace.clear(); // clear because of replay
      trace.add("started");
      Async.retry(
              retryOptions,
              () -> {
                trace.add("retry at " + Workflow.currentTimeMillis());
                return Workflow.newFailedPromise(new IllegalThreadStateException("simulated"));
              })
          .get();
      trace.add("beforeSleep");
      Workflow.sleep(60000);
      trace.add("done");
      return "";
    }
  }

  /** @see DeterministicRunnerTest#testRetry() */
  @Test
  public void testAsyncRetry() {
    startWorkerFor(TestAsyncRetryWorkflowImpl.class);
    TestWorkflow2 client =
        workflowClient.newWorkflowStub(
            TestWorkflow2.class, newWorkflowOptionsBuilder(taskList).build());
    String result = null;
    try {
      result = client.execute(useExternalService);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof IllegalThreadStateException);
      assertEquals("simulated", e.getCause().getMessage());
    }
    assertNull(result);
    assertEquals(
        TestAsyncRetryWorkflowImpl.trace.toString(), 3, TestAsyncRetryWorkflowImpl.trace.size());
    assertEquals("started", TestAsyncRetryWorkflowImpl.trace.get(0));
    assertTrue(TestAsyncRetryWorkflowImpl.trace.get(1).startsWith("retry at "));
    assertTrue(TestAsyncRetryWorkflowImpl.trace.get(2).startsWith("retry at "));
  }

  public interface TestExceptionPropagation {

    @WorkflowMethod
    void execute(String taskList);
  }

  public static class ThrowingChild implements TestWorkflow1 {

    @Override
    @SuppressWarnings("AssertionFailureIgnored")
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions2());
      try {
        testActivities.throwIO();
        fail("unreachable");
        return "ignored";
      } catch (ActivityFailureException e) {
        try {
          assertTrue(e.getMessage().contains("TestActivities::throwIO"));
          assertTrue(e.getCause() instanceof IOException);
          assertEquals("simulated IO problem", e.getCause().getMessage());
        } catch (AssertionError ae) {
          // Errors cause decision to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
        Exception ee = new NumberFormatException();
        ee.initCause(e);
        throw Workflow.wrap(ee);
      }
    }
  }

  public static class TestExceptionPropagationImpl implements TestExceptionPropagation {

    @Override
    @SuppressWarnings("AssertionFailureIgnored")
    public void execute(String taskList) {
      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder()
              .setExecutionStartToCloseTimeout(Duration.ofHours(1))
              .build();
      TestWorkflow1 child = Workflow.newWorkflowStub(TestWorkflow1.class, options);
      try {
        child.execute(taskList);
        fail("unreachable");
      } catch (RuntimeException e) {
        try {
          assertNoEmptyStacks(e);
          assertTrue(e.getMessage().contains("TestWorkflow1::execute"));
          assertTrue(e instanceof ChildWorkflowException);
          assertTrue(e.getCause() instanceof NumberFormatException);
          assertTrue(e.getCause().getCause() instanceof ActivityFailureException);
          assertTrue(e.getCause().getCause().getCause() instanceof IOException);
          assertEquals("simulated IO problem", e.getCause().getCause().getCause().getMessage());
        } catch (AssertionError ae) {
          // Errors cause decision to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
        Exception fnf = new FileNotFoundException();
        fnf.initCause(e);
        throw Workflow.wrap(fnf);
      }
    }
  }

  private static void assertNoEmptyStacks(RuntimeException e) {
    // Check that there are no empty stacks
    Throwable c = e;
    while (c != null) {
      assertTrue(c.getStackTrace().length > 0);
      c = c.getCause();
    }
  }

  /**
   * Test that an NPE thrown in an activity executed from a child workflow results in the following
   * chain of exceptions when an exception is received in an external client that executed workflow
   * through a WorkflowClient:
   *
   * <pre>
   * {@link WorkflowFailureException}
   *     ->{@link ChildWorkflowFailureException}
   *         ->{@link ActivityFailureException}
   *             ->OriginalActivityException
   * </pre>
   *
   * This test also tests that Checked exception wrapping and unwrapping works producing a nice
   * exception chain without the wrappers.
   */
  @Test
  public void testExceptionPropagation() {
    startWorkerFor(ThrowingChild.class, TestExceptionPropagationImpl.class);
    TestExceptionPropagation client =
        workflowClient.newWorkflowStub(
            TestExceptionPropagation.class, newWorkflowOptionsBuilder(taskList).build());
    try {
      client.execute(taskList);
      fail("Unreachable");
    } catch (WorkflowFailureException e) {
      // Rethrow the assertion failure
      if (e.getCause().getCause() instanceof AssertionError) {
        throw (AssertionError) e.getCause().getCause();
      }
      assertNoEmptyStacks(e);
      // Uncomment to see the actual trace.
      //            e.printStackTrace();
      assertTrue(e.getMessage(), e.getMessage().contains("TestExceptionPropagation::execute"));
      assertTrue(e.getStackTrace().length > 0);
      assertTrue(e.getCause() instanceof FileNotFoundException);
      assertTrue(e.getCause().getCause() instanceof ChildWorkflowException);
      assertTrue(e.getCause().getCause().getCause() instanceof NumberFormatException);
      assertTrue(e.getCause().getCause().getCause().getCause() instanceof ActivityFailureException);
      assertTrue(e.getCause().getCause().getCause().getCause().getCause() instanceof IOException);
      assertEquals(
          "simulated IO problem",
          e.getCause().getCause().getCause().getCause().getCause().getMessage());
    }
  }

  public interface QueryableWorkflow {

    @WorkflowMethod
    String execute();

    @QueryMethod
    String getState();

    @SignalMethod(name = "testSignal")
    void mySignal(String value);
  }

  public static class TestSignalWorkflowImpl implements QueryableWorkflow {

    String state = "initial";
    List<String> signals = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return signals.get(0) + signals.get(1);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void mySignal(String value) {
      log.info("TestSignalWorkflowImpl.mySignal value=" + value);
      state = value;
      signals.add(value);
      if (signals.size() == 2) {
        promise.complete(null);
      }
    }
  }

  @Test
  public void testSignal() throws Exception {
    AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    startWorkerFor(TestSignalWorkflowImpl.class);
    WorkflowOptions.Builder optionsBuilder = newWorkflowOptionsBuilder(taskList);
    String workflowId = UUID.randomUUID().toString();
    optionsBuilder.setWorkflowId(workflowId);
    AtomicReference<QueryableWorkflow> client = new AtomicReference<>();
    registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          assertEquals(workflowId, execution.get().getWorkflowId());
          assertEquals("initial", client.get().getState());
          client.get().mySignal("Hello ");
          try {
            Thread.sleep(200);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }

          // Test client.get() created using WorkflowExecution
          client.set(
              workflowClient.newWorkflowStub(
                  QueryableWorkflow.class,
                  execution.get().getWorkflowId(),
                  Optional.of(execution.get().getRunId())));
          assertEquals("Hello ", client.get().getState());

          // Test query through replay by a local worker.
          Worker queryWorker;
          if (useExternalService) {
            queryWorker = new Worker(domain, taskList);
          } else {
            queryWorker = workflowEnvironment.newWorker(taskList);
          }
          queryWorker.registerWorkflowImplementationTypes(TestSignalWorkflowImpl.class);
          String queryResult = null;
          try {
            queryResult =
                queryWorker.queryWorkflowExecution(
                    execution.get(), "QueryableWorkflow::getState", String.class);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          assertEquals("Hello ", queryResult);
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          client.get().mySignal("World!");
          assertEquals("World!", client.get().getState());
          assertEquals(
              "Hello World!",
              workflowClient
                  .newUntypedWorkflowStub(execution.get(), Optional.empty())
                  .getResult(String.class));
        });
    client.set(workflowClient.newWorkflowStub(QueryableWorkflow.class, optionsBuilder.build()));
    // To execute workflow client.execute() would do. But we want to start workflow and immediately return.
    execution.set(WorkflowClient.start(client.get()::execute));
  }

  @Test
  public void testSignalUntyped() {
    startWorkerFor(TestSignalWorkflowImpl.class);
    String workflowType = QueryableWorkflow.class.getSimpleName() + "::execute";
    AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    UntypedWorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            workflowType, newWorkflowOptionsBuilder(taskList).build());
    // To execute workflow client.execute() would do. But we want to start workflow and immediately return.
    registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          assertEquals("initial", client.query("QueryableWorkflow::getState", String.class));
          client.signal("testSignal", "Hello ");
          while (!"Hello ".equals(client.query("QueryableWorkflow::getState", String.class))) {}
          assertEquals("Hello ", client.query("QueryableWorkflow::getState", String.class));
          client.signal("testSignal", "World!");
          while (!"World!".equals(client.query("QueryableWorkflow::getState", String.class))) {}
          assertEquals("World!", client.query("QueryableWorkflow::getState", String.class));
          assertEquals(
              "Hello World!",
              workflowClient
                  .newUntypedWorkflowStub(execution.get(), Optional.of(workflowType))
                  .getResult(String.class));
        });
    execution.set(client.start());
    assertEquals("Hello World!", client.getResult(String.class));
  }

  static final AtomicInteger decisionCount = new AtomicInteger();
  static final CompletableFuture<Boolean> sendSignal = new CompletableFuture<>();

  public static class TestSignalDuringLastDecisionWorkflowImpl implements TestWorkflowSignaled {

    private String signal;

    @Override
    public String execute() {
      if (decisionCount.incrementAndGet() == 1) {
        sendSignal.complete(true);
        // Never sleep in a real workflow using Thread.sleep.
        // Here it is to simulate a race condition.
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      return signal;
    }

    @Override
    public void signal1(String arg) {
      signal = arg;
    }
  }

  @Test
  public void testSignalDuringLastDecision() throws InterruptedException {
    startWorkerFor(TestSignalDuringLastDecisionWorkflowImpl.class);
    WorkflowOptions.Builder options = newWorkflowOptionsBuilder(taskList);
    options.setWorkflowId("testSignalDuringLastDecision-" + UUID.randomUUID().toString());
    TestWorkflowSignaled client =
        workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options.build());
    WorkflowExecution execution = WorkflowClient.start(client::execute);
    registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          try {
            try {
              sendSignal.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            client.signal1("Signal Input");
          } catch (TimeoutException | ExecutionException e) {
            throw new RuntimeException(e);
          }
          assertEquals(
              "Signal Input",
              workflowClient
                  .newUntypedWorkflowStub(execution, Optional.empty())
                  .getResult(String.class));
        });
  }

  public static class TestTimerCallbackBlockedWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      Promise<Void> timer1 = Workflow.newTimer(Duration.ZERO);
      Promise<Void> timer2 = Workflow.newTimer(Duration.ofSeconds(1));

      return timer1
          .thenApply(
              (e) -> {
                timer2.get();
                return "timer2Fired";
              })
          .get();
    }
  }

  /** Test that it is not allowed to block in the timer callback thread. */
  @Test
  public void testTimerCallbackBlocked() {
    startWorkerFor(TestTimerCallbackBlockedWorkflowImpl.class);
    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(10));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(1));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    String result = client.execute(taskList);
    assertEquals("timer2Fired", result);
  }

  public interface ITestChild {

    @WorkflowMethod
    String execute(String arg);
  }

  public interface ITestNamedChild {

    @WorkflowMethod(name = "namedChild")
    String execute(String arg);
  }

  private static String child2Id = UUID.randomUUID().toString();

  public static class TestParentWorkflow implements TestWorkflow1 {

    private final ITestChild child1 = Workflow.newWorkflowStub(ITestChild.class);
    private final ITestNamedChild child2;

    public TestParentWorkflow() {
      ChildWorkflowOptions.Builder options = new ChildWorkflowOptions.Builder();
      options.setWorkflowId(child2Id);
      child2 = Workflow.newWorkflowStub(ITestNamedChild.class, options.build());
    }

    @Override
    public String execute(String taskList) {
      Promise<String> r1 = Async.function(child1::execute, "Hello ");
      String r2 = child2.execute("World!");
      assertEquals(child2Id, Workflow.getChildWorkflowExecution(child2).get().getWorkflowId());
      return r1.get() + r2;
    }
  }

  public static class TestChild implements ITestChild {

    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  public static class TestNamedChild implements ITestNamedChild {

    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  @Test
  public void testChildWorkflow() {
    startWorkerFor(TestParentWorkflow.class, TestNamedChild.class, TestChild.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    assertEquals("HELLO WORLD!", client.execute(taskList));
  }

  public static class TestChildWorkflowRetryWorkflow implements TestWorkflow1 {

    private ITestChild child;

    public TestChildWorkflowRetryWorkflow() {}

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder()
              .setExecutionStartToCloseTimeout(Duration.ofSeconds(500))
              .setTaskStartToCloseTimeout(Duration.ofSeconds(2))
              .setTaskList(taskList)
              .setRetryOptions(
                  new RetryOptions.Builder()
                      .setMinimumAttempts(2)
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      child = Workflow.newWorkflowStub(ITestChild.class, options);

      return child.execute(taskList);
    }
  }

  public interface AngryChildActivity {

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 5)
    void execute();
  }

  public static class AngryChildActivityImpl implements AngryChildActivity {

    private long invocationCount;

    @Override
    public void execute() {
      invocationCount++;
    }

    public long getInvocationCount() {
      return invocationCount;
    }
  }

  public static class AngryChild implements ITestChild {

    @Override
    public String execute(String taskList) {
      AngryChildActivity activity =
          Workflow.newActivityStub(
              AngryChildActivity.class,
              new ActivityOptions.Builder().setTaskList(taskList).build());
      activity.execute();
      throw new UnsupportedOperationException("simulated failure");
    }
  }

  @Test
  public void testChildWorkflowRetry() {
    AngryChildActivityImpl angryChildActivity = new AngryChildActivityImpl();
    worker.registerActivitiesImplementations(angryChildActivity);
    startWorkerFor(TestChildWorkflowRetryWorkflow.class, AngryChild.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    try {
      client.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailureException);
      assertTrue(e.getCause().getCause() instanceof UnsupportedOperationException);
      assertEquals("simulated failure", e.getCause().getCause().getMessage());
    }
    assertEquals(3, angryChildActivity.getInvocationCount());
  }

  public static class TestSignalExternalWorkflow implements TestWorkflowSignaled {

    private final SignalingChild child = Workflow.newWorkflowStub(SignalingChild.class);

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          Async.function(child::execute, "Hello", Workflow.getWorkflowInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal1(String arg) {
      fromSignal.complete(arg);
    }
  }

  public interface SignalingChild {

    @WorkflowMethod
    String execute(String arg, String parentWorkflowID);
  }

  public static class SignalingChildImpl implements SignalingChild {

    @Override
    public String execute(String greeting, String parentWorkflowID) {
      WorkflowExecution parentExecution = new WorkflowExecution().setWorkflowId(parentWorkflowID);
      TestWorkflowSignaled parent =
          Workflow.newWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      parent.signal1("World");
      return greeting;
    }
  }

  @Test
  public void testSignalExternalWorkflow() {
    startWorkerFor(TestSignalExternalWorkflow.class, SignalingChildImpl.class);
    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
    options.setTaskList(taskList);
    TestWorkflowSignaled client =
        workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options.build());
    assertEquals("Hello World!", client.execute());
  }

  public static class TestSignalExternalWorkflowFailure implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      WorkflowExecution parentExecution = new WorkflowExecution().setWorkflowId("invalid id");
      TestWorkflowSignaled workflow =
          Workflow.newWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      workflow.signal1("World");
      return "ignored";
    }
  }

  @Test
  public void testSignalExternalWorkflowFailure() {
    startWorkerFor(TestSignalExternalWorkflowFailure.class);
    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    try {
      client.execute(taskList);
      fail("unreachable");
    } catch (WorkflowFailureException e) {
      assertTrue(e.getCause() instanceof SignalExternalWorkflowException);
      assertEquals(
          "invalid id",
          ((SignalExternalWorkflowException) e.getCause()).getSignaledExecution().getWorkflowId());
      assertEquals(
          SignalExternalWorkflowExecutionFailedCause.UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION,
          ((SignalExternalWorkflowException) e.getCause()).getFailureCause());
    }
  }

  public static class TestSignalExternalWorkflowImmediateCancellation implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      WorkflowExecution parentExecution = new WorkflowExecution().setWorkflowId("invalid id");
      TestWorkflowSignaled workflow =
          Workflow.newWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      CompletablePromise<Void> signal = Workflow.newPromise();
      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> signal.completeFrom(Async.procedure(workflow::signal1, "World")));
      scope.cancel();
      try {
        signal.get();
      } catch (IllegalArgumentException e) {
        // expected
      }
      return "result";
    }
  }

  @Test
  public void testSignalExternalWorkflowImmediateCancellation() {
    startWorkerFor(TestSignalExternalWorkflowImmediateCancellation.class);
    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    try {
      client.execute(taskList);
      fail("unreachable");
    } catch (WorkflowFailureException e) {
      assertTrue(e.getCause() instanceof CancellationException);
    }
  }

  public static class TestChildWorkflowAsyncRetryWorkflow implements TestWorkflow1 {

    private ITestChild child;

    public TestChildWorkflowAsyncRetryWorkflow() {}

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder()
              .setExecutionStartToCloseTimeout(Duration.ofSeconds(5))
              .setTaskStartToCloseTimeout(Duration.ofSeconds(2))
              .setTaskList(taskList)
              .setRetryOptions(
                  new RetryOptions.Builder()
                      .setMinimumAttempts(2)
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      child = Workflow.newWorkflowStub(ITestChild.class, options);
      return Async.function(child::execute, taskList).get();
    }
  }

  @Test
  public void testChildWorkflowAsyncRetry() {
    AngryChildActivityImpl angryChildActivity = new AngryChildActivityImpl();
    worker.registerActivitiesImplementations(angryChildActivity);
    startWorkerFor(TestChildWorkflowAsyncRetryWorkflow.class, AngryChild.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(20));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(2));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    try {
      client.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailureException);
      assertTrue(e.getCause().getCause() instanceof UnsupportedOperationException);
      assertEquals("simulated failure", e.getCause().getCause().getMessage());
    }
    assertEquals(3, angryChildActivity.getInvocationCount());
  }

  private static int testDecisionFailureBackoffReplayCount;

  public static class TestDecisionFailureBackoff implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      if (testDecisionFailureBackoffReplayCount++ < 2) {
        throw new Error("simulated decision failure");
      }
      return "result1";
    }
  }

  @Test
  public void testDecisionFailureBackoff() {
    testDecisionFailureBackoffReplayCount = 0;
    startWorkerFor(TestDecisionFailureBackoff.class);
    WorkflowOptions o =
        new WorkflowOptions.Builder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(10))
            .setTaskStartToCloseTimeout(Duration.ofSeconds(1))
            .setTaskList(taskList)
            .build();

    TestWorkflow1 workflowStub = workflowClient.newWorkflowStub(TestWorkflow1.class, o);
    long start = System.currentTimeMillis();
    String result = workflowStub.execute(taskList);
    long elapsed = System.currentTimeMillis() - start;
    assertTrue("spinned on fail decision", elapsed > 1000);
    assertEquals("result1", result);
  }

  public static class TestAwait implements TestWorkflow1 {

    private int i;
    private int j;

    @Override
    public String execute(String taskList) {
      StringBuilder result = new StringBuilder();
      Async.procedure(
          () -> {
            while (true) {
              Workflow.await(() -> i > j);
              result.append(" awoken i=" + i);
              j++;
            }
          });

      for (i = 1; i < 3; i++) {
        Workflow.await(() -> j >= i);
        result.append(" loop i=" + i);
      }
      assertFalse(Workflow.await(Duration.ZERO, () -> false));
      return result.toString();
    }
  }

  @Test
  public void testAwait() {
    startWorkerFor(TestAwait.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals(" awoken i=1 loop i=1 awoken i=2 loop i=2", result);
  }

  public interface TestActivities {

    String activityWithDelay(long milliseconds, boolean heartbeatMoreThanOnce);

    String activity();

    @ActivityMethod(name = "customActivity1")
    String activity1(String input);

    String activity2(String a1, int a2);

    String activity3(String a1, int a2, int a3);

    String activity4(String a1, int a2, int a3, int a4);

    String activity5(String a1, int a2, int a3, int a4, int a5);

    String activity6(String a1, int a2, int a3, int a4, int a5, int a6);

    void proc();

    void proc1(String input);

    void proc2(String a1, int a2);

    void proc3(String a1, int a2, int a3);

    void proc4(String a1, int a2, int a3, int a4);

    void proc5(String a1, int a2, int a3, int a4, int a5);

    void proc6(String a1, int a2, int a3, int a4, int a5, int a6);

    void throwIO();

    @ActivityMethod(
      scheduleToStartTimeoutSeconds = 5,
      scheduleToCloseTimeoutSeconds = 5,
      heartbeatTimeoutSeconds = 5,
      startToCloseTimeoutSeconds = 10
    )
    @MethodRetry(
      initialIntervalSeconds = 1,
      maximumIntervalSeconds = 1,
      minimumAttempts = 2,
      maximumAttempts = 3
    )
    void throwIOAnnotated();
  }

  private static class TestActivitiesImpl implements TestActivities {

    final ActivityCompletionClient completionClient;
    final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
    final List<String> procResult = Collections.synchronizedList(new ArrayList<>());
    private final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(2, 100, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());

    private TestActivitiesImpl(ActivityCompletionClient completionClient) {
      this.completionClient = completionClient;
    }

    void close() {
      executor.shutdown();
    }

    void assertInvocations(String... expected) {
      assertEquals(Arrays.asList(expected), invocations);
    }

    @Override
    public String activityWithDelay(long delay, boolean heartbeatMoreThanOnce) {
      byte[] taskToken = Activity.getTaskToken();
      executor.execute(
          () -> {
            invocations.add("activityWithDelay");
            long start = System.currentTimeMillis();
            try {
              int count = 0;
              while (System.currentTimeMillis() - start < delay) {
                if (heartbeatMoreThanOnce || count == 0) {
                  completionClient.heartbeat(taskToken, "heartbeatValue");
                }
                count++;
                Thread.sleep(100);
              }
              completionClient.complete(taskToken, "activity");
            } catch (InterruptedException e) {
              throw new RuntimeException("unexpected", e);
            } catch (ActivityNotExistsException | ActivityCancelledException e) {
              completionClient.reportCancellation(taskToken, null);
            }
          });
      Activity.doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activity() {
      invocations.add("activity");
      return "activity";
    }

    @Override
    public String activity1(String a1) {
      invocations.add("activity1");
      return a1;
    }

    @Override
    public String activity2(String a1, int a2) {
      invocations.add("activity2");
      return a1 + a2;
    }

    @Override
    public String activity3(String a1, int a2, int a3) {
      invocations.add("activity3");
      return a1 + a2 + a3;
    }

    @Override
    public String activity4(String a1, int a2, int a3, int a4) {
      byte[] taskToken = Activity.getTaskToken();
      executor.execute(
          () -> {
            invocations.add("activity4");
            completionClient.complete(taskToken, a1 + a2 + a3 + a4);
          });
      Activity.doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activity5(String a1, int a2, int a3, int a4, int a5) {
      WorkflowExecution execution = Activity.getWorkflowExecution();
      String id = Activity.getTask().getActivityId();
      executor.execute(
          () -> {
            invocations.add("activity5");
            completionClient.complete(execution, id, a1 + a2 + a3 + a4 + a5);
          });
      Activity.doNotCompleteOnReturn();
      return "ignored";
    }

    @Override
    public String activity6(String a1, int a2, int a3, int a4, int a5, int a6) {
      invocations.add("activity6");
      return a1 + a2 + a3 + a4 + a5 + a6;
    }

    @Override
    public void proc() {
      invocations.add("proc");
      procResult.add("proc");
    }

    @Override
    public void proc1(String a1) {
      invocations.add("proc1");
      procResult.add(a1);
    }

    @Override
    public void proc2(String a1, int a2) {
      invocations.add("proc2");
      procResult.add(a1 + a2);
    }

    @Override
    public void proc3(String a1, int a2, int a3) {
      invocations.add("proc3");
      procResult.add(a1 + a2 + a3);
    }

    @Override
    public void proc4(String a1, int a2, int a3, int a4) {
      invocations.add("proc4");
      procResult.add(a1 + a2 + a3 + a4);
    }

    @Override
    public void proc5(String a1, int a2, int a3, int a4, int a5) {
      invocations.add("proc5");
      procResult.add(a1 + a2 + a3 + a4 + a5);
    }

    @Override
    public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
      invocations.add("proc6");
      procResult.add(a1 + a2 + a3 + a4 + a5 + a6);
    }

    @Override
    public void throwIO() {
      invocations.add("throwIO");
      try {
        throw new IOException("simulated IO problem");
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }

    @Override
    public void throwIOAnnotated() {
      invocations.add("throwIOAnnotated");
      try {
        throw new IOException("simulated IO problem");
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }
  }

  public interface TestMultiargsWorkflowsFunc {

    @WorkflowMethod
    String func();
  }

  public interface TestMultiargsWorkflowsFunc1 {

    @WorkflowMethod(
      name = "func1",
      taskList = ANNOTATION_TASK_LIST,
      workflowIdReusePolicy = WorkflowIdReusePolicy.RejectDuplicate,
      executionStartToCloseTimeoutSeconds = 10
    )
    String func1(String input);
  }

  public interface TestMultiargsWorkflowsFunc2 {

    @WorkflowMethod
    String func2(String a1, int a2);
  }

  public interface TestMultiargsWorkflowsFunc3 {

    @WorkflowMethod
    String func3(String a1, int a2, int a3);
  }

  public interface TestMultiargsWorkflowsFunc4 {

    @WorkflowMethod
    String func4(String a1, int a2, int a3, int a4);
  }

  public interface TestMultiargsWorkflowsFunc5 {

    @WorkflowMethod
    String func5(String a1, int a2, int a3, int a4, int a5);
  }

  public interface TestMultiargsWorkflowsFunc6 {

    @WorkflowMethod
    String func6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public interface TestMultiargsWorkflowsProc {

    @WorkflowMethod
    void proc();
  }

  public interface TestMultiargsWorkflowsProc1 {

    @WorkflowMethod
    void proc1(String input);
  }

  public interface TestMultiargsWorkflowsProc2 {

    @WorkflowMethod
    void proc2(String a1, int a2);
  }

  public interface TestMultiargsWorkflowsProc3 {

    @WorkflowMethod
    void proc3(String a1, int a2, int a3);
  }

  public interface TestMultiargsWorkflowsProc4 {

    @WorkflowMethod
    void proc4(String a1, int a2, int a3, int a4);
  }

  public interface TestMultiargsWorkflowsProc5 {

    @WorkflowMethod
    void proc5(String a1, int a2, int a3, int a4, int a5);
  }

  public interface TestMultiargsWorkflowsProc6 {

    @WorkflowMethod
    void proc6(String a1, int a2, int a3, int a4, int a5, int a6);
  }

  public static class TestMultiargsWorkflowsImpl
      implements TestMultiargsWorkflowsFunc,
          TestMultiargsWorkflowsFunc1,
          TestMultiargsWorkflowsFunc2,
          TestMultiargsWorkflowsFunc3,
          TestMultiargsWorkflowsFunc4,
          TestMultiargsWorkflowsFunc5,
          TestMultiargsWorkflowsFunc6,
          TestMultiargsWorkflowsProc,
          TestMultiargsWorkflowsProc1,
          TestMultiargsWorkflowsProc2,
          TestMultiargsWorkflowsProc3,
          TestMultiargsWorkflowsProc4,
          TestMultiargsWorkflowsProc5,
          TestMultiargsWorkflowsProc6 {

    static List<String> procResult = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String func() {
      return "func";
    }

    @Override
    public String func1(String a1) {
      return a1;
    }

    @Override
    public String func2(String a1, int a2) {
      return a1 + a2;
    }

    @Override
    public String func3(String a1, int a2, int a3) {
      return a1 + a2 + a3;
    }

    @Override
    public String func4(String a1, int a2, int a3, int a4) {
      return a1 + a2 + a3 + a4;
    }

    @Override
    public String func5(String a1, int a2, int a3, int a4, int a5) {
      return a1 + a2 + a3 + a4 + a5;
    }

    @Override
    public String func6(String a1, int a2, int a3, int a4, int a5, int a6) {
      return a1 + a2 + a3 + a4 + a5 + a6;
    }

    @Override
    public void proc() {
      procResult.add("proc");
    }

    @Override
    public void proc1(String a1) {
      procResult.add(a1);
    }

    @Override
    public void proc2(String a1, int a2) {
      procResult.add(a1 + a2);
    }

    @Override
    public void proc3(String a1, int a2, int a3) {
      procResult.add(a1 + a2 + a3);
    }

    @Override
    public void proc4(String a1, int a2, int a3, int a4) {
      procResult.add(a1 + a2 + a3 + a4);
    }

    @Override
    public void proc5(String a1, int a2, int a3, int a4, int a5) {
      procResult.add(a1 + a2 + a3 + a4 + a5);
    }

    @Override
    public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
      procResult.add(a1 + a2 + a3 + a4 + a5 + a6);
    }
  }
}
