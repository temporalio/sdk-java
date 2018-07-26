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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.client.ActivityCancelledException;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.ActivityNotExistsException;
import com.uber.cadence.client.DuplicateWorkflowException;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientInterceptorBase;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowException;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.sync.DeterministicRunnerTest;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestEnvironmentOptions.Builder;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerOptions;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Functions.Func1;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
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
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class WorkflowTest {

  /**
   * When set to true increases test, activity and workflow timeouts to large values to support
   * stepping through code in a debugger without timing out.
   */
  private static final boolean DEBUGGER_TIMEOUTS = false;

  public static final String ANNOTATION_TASK_LIST = "WorkflowTest-testExecute[Docker]";

  private TracingWorkflowInterceptorFactory tracer;
  private static final boolean skipDockerService =
      Boolean.parseBoolean(System.getenv("SKIP_DOCKER_SERVICE"));

  @Parameters(name = "{1}")
  public static Object[] data() {
    if (skipDockerService) {
      return new Object[][] {{false, "TestService"}};
    } else {
      return new Object[][] {{true, "Docker"}, {false, "TestService"}};
    }
  }

  @Rule public TestName testName = new TestName();

  @Rule
  public Timeout globalTimeout =
      Timeout.seconds(DEBUGGER_TIMEOUTS ? 500 : (skipDockerService ? 5 : 20));

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (tracer != null) {
            System.err.println("TRACE:\n" + tracer.getTrace());
          }
          if (testEnvironment != null) {
            System.err.println("HISTORIES:\n" + testEnvironment.getDiagnostics());
          }
        }
      };

  @Parameter public boolean useExternalService;

  @Parameter(1)
  public String testType;

  public static final String DOMAIN = "UnitTest";
  private static final Logger log = LoggerFactory.getLogger(WorkflowTest.class);

  private static String UUID_REGEXP =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private String taskList;

  private Worker worker;
  private TestActivitiesImpl activitiesImpl;
  private WorkflowClient workflowClient;
  private WorkflowClient workflowClientWithOptions;
  private TestWorkflowEnvironment testEnvironment;
  private ScheduledExecutorService scheduledExecutor;
  private List<ScheduledFuture<?>> delayedCallbacks = new ArrayList<>();
  private static WorkflowServiceTChannel service = new WorkflowServiceTChannel();

  private static WorkflowOptions.Builder newWorkflowOptionsBuilder(String taskList) {
    if (DEBUGGER_TIMEOUTS) {
      return new WorkflowOptions.Builder()
          .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
          .setTaskStartToCloseTimeout(Duration.ofSeconds(60))
          .setTaskList(taskList);
    } else {
      return new WorkflowOptions.Builder()
          .setExecutionStartToCloseTimeout(Duration.ofSeconds(30))
          .setTaskStartToCloseTimeout(Duration.ofSeconds(5))
          .setTaskList(taskList);
    }
  }

  private static ActivityOptions newActivityOptions1(String taskList) {
    if (DEBUGGER_TIMEOUTS) {
      return new ActivityOptions.Builder()
          .setTaskList(taskList)
          .setScheduleToCloseTimeout(Duration.ofSeconds(5))
          .setHeartbeatTimeout(Duration.ofSeconds(5))
          .setScheduleToStartTimeout(Duration.ofSeconds(5))
          .setStartToCloseTimeout(Duration.ofSeconds(10))
          .build();
    } else {
      return new ActivityOptions.Builder()
          .setTaskList(taskList)
          .setScheduleToCloseTimeout(Duration.ofSeconds(1000))
          .setHeartbeatTimeout(Duration.ofSeconds(1000))
          .setScheduleToStartTimeout(Duration.ofSeconds(1000))
          .setStartToCloseTimeout(Duration.ofSeconds(10000))
          .build();
    }
  }

  private static ActivityOptions newActivityOptions2() {
    return new ActivityOptions.Builder().setScheduleToCloseTimeout(Duration.ofSeconds(20)).build();
  }

  @Before
  public void setUp() {
    String testMethod = testName.getMethodName();
    if (testMethod.startsWith("testExecute") || testMethod.startsWith("testStart")) {
      taskList = ANNOTATION_TASK_LIST;
    } else {
      taskList = "WorkflowTest-" + testMethod + "-" + UUID.randomUUID().toString();
    }
    tracer = new TracingWorkflowInterceptorFactory();
    // TODO: Create a version of TestWorkflowEnvironment that runs against a real service.
    if (useExternalService) {
      WorkerOptions workerOptions =
          new WorkerOptions.Builder().setInterceptorFactory(tracer).build();
      Worker.Factory workerFactory = new Worker.Factory(service, DOMAIN);
      worker = workerFactory.newWorker(taskList, workerOptions);
      workflowClient = WorkflowClient.newInstance(DOMAIN);
      WorkflowClientOptions clientOptions =
          new WorkflowClientOptions.Builder()
              .setDataConverter(JsonDataConverter.getInstance())
              .build();
      workflowClientWithOptions = WorkflowClient.newInstance(DOMAIN, clientOptions);
      scheduledExecutor = new ScheduledThreadPoolExecutor(1);
    } else {
      TestEnvironmentOptions testOptions =
          new Builder().setDomain(DOMAIN).setInterceptorFactory(tracer).build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      worker = testEnvironment.newWorker(taskList);
      workflowClient = testEnvironment.newWorkflowClient();
      workflowClientWithOptions = testEnvironment.newWorkflowClient();
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
    if (tracer != null) {
      tracer.assertExpected();
    }
  }

  private void startWorkerFor(Class<?>... workflowTypes) {
    worker.registerWorkflowImplementationTypes(workflowTypes);
    testEnvironment.start();
  }

  void registerDelayedCallback(Duration delay, Runnable r) {
    if (useExternalService) {
      ScheduledFuture<?> result =
          scheduledExecutor.schedule(r, delay.toMillis(), TimeUnit.MILLISECONDS);
      delayedCallbacks.add(result);
    } else {
      testEnvironment.registerDelayedCallback(delay, r);
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

    @QueryMethod(name = "getTrace")
    List<String> getTrace();
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
    tracer.setExpected(
        "sleep PT2S",
        "executeActivity TestActivities::activityWithDelay",
        "executeActivity TestActivities::activity2");
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

  public static class TestActivityRetryOptionsChange implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ActivityOptions.Builder options =
          new ActivityOptions.Builder()
              .setTaskList(taskList)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setScheduleToStartTimeout(Duration.ofSeconds(5))
              .setStartToCloseTimeout(Duration.ofSeconds(10));
      RetryOptions retryOptions;
      if (Workflow.isReplaying()) {
        retryOptions =
            new RetryOptions.Builder()
                .setMinimumAttempts(1)
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .build();
      } else {
        retryOptions =
            new RetryOptions.Builder()
                .setMinimumAttempts(2)
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(2)
                .build();
      }
      TestActivities activities = Workflow.newActivityStub(TestActivities.class, options.build());
      Workflow.retry(retryOptions, () -> activities.throwIO());
      return "ignored";
    }
  }

  @Test
  public void testActivityRetryOptionsChange() {
    startWorkerFor(TestActivityRetryOptionsChange.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    try {
      workflowStub.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause().getCause() instanceof IOException);
    }
    assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  public static class TestUntypedActivityRetry implements TestWorkflow1 {

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
      ActivityStub activities = Workflow.newUntypedActivityStub(options);
      activities.execute("TestActivities::throwIO", Void.class);
      return "ignored";
    }
  }

  @Test
  public void testUntypedActivityRetry() {
    startWorkerFor(TestUntypedActivityRetry.class);
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

  public static class TestAsyncActivityRetryOptionsChange implements TestWorkflow1 {

    private TestActivities activities;

    @Override
    public String execute(String taskList) {
      ActivityOptions.Builder options =
          new ActivityOptions.Builder()
              .setTaskList(taskList)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .setScheduleToStartTimeout(Duration.ofSeconds(5))
              .setStartToCloseTimeout(Duration.ofSeconds(10));
      if (Workflow.isReplaying()) {
        options.setRetryOptions(
            new RetryOptions.Builder()
                .setMinimumAttempts(1)
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setDoNotRetry(NullPointerException.class)
                .setMaximumAttempts(3)
                .build());
      } else {
        options.setRetryOptions(
            new RetryOptions.Builder()
                .setMinimumAttempts(2)
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(2)
                .setDoNotRetry(NullPointerException.class)
                .build());
      }
      this.activities = Workflow.newActivityStub(TestActivities.class, options.build());
      Async.procedure(activities::throwIO).get();
      return "ignored";
    }
  }

  @Test
  public void testAsyncActivityRetryOptionsChange() {
    startWorkerFor(TestAsyncActivityRetryOptionsChange.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    try {
      workflowStub.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause().getCause() instanceof IOException);
    }
    assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
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
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1::execute", newWorkflowOptionsBuilder(taskList).build());
    WorkflowExecution execution = workflowStub.start(taskList);
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
    WorkflowStub client =
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
    WorkflowStub client =
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
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1::execute", newWorkflowOptionsBuilder(taskList).build());
    client.start(taskList);
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
    tracer.setExpected("continueAsNew", "continueAsNew", "continueAsNew", "continueAsNew");
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

      // Test serialization of generic data structure
      List<UUID> uuids = new ArrayList<>();
      uuids.add(Workflow.randomUUID());
      uuids.add(Workflow.randomUUID());
      List<UUID> uuidsResult = Async.function(testActivities::activityUUIDList, uuids).get();
      assertEquals(uuids, uuidsResult);
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

  public static class TestAsyncUtypedActivityWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ActivityStub testActivities = Workflow.newUntypedActivityStub(newActivityOptions2());
      Promise<String> a =
          Async.function(testActivities::<String>execute, "TestActivities::activity", String.class);
      Promise<String> a1 =
          Async.function(
              testActivities::<String>execute,
              "customActivity1",
              String.class,
              "1"); // name overridden in annotation
      Promise<String> a2 =
          Async.function(
              testActivities::<String>execute, "TestActivities::activity2", String.class, "1", 2);
      Promise<String> a3 =
          Async.function(
              testActivities::<String>execute,
              "TestActivities::activity3",
              String.class,
              "1",
              2,
              3);
      Promise<String> a4 =
          Async.function(
              testActivities::<String>execute,
              "TestActivities::activity4",
              String.class,
              "1",
              2,
              3,
              4);
      assertEquals("activity", a.get());
      assertEquals("1", a1.get());
      assertEquals("12", a2.get());
      assertEquals("123", a3.get());
      assertEquals("1234", a4.get());

      Async.procedure(testActivities::<Void>execute, "TestActivities::proc", Void.class).get();
      Async.procedure(testActivities::<Void>execute, "TestActivities::proc1", Void.class, "1")
          .get();
      Async.procedure(testActivities::<Void>execute, "TestActivities::proc2", Void.class, "1", 2)
          .get();
      Async.procedure(testActivities::<Void>execute, "TestActivities::proc3", Void.class, "1", 2, 3)
          .get();
      Async.procedure(
              testActivities::<Void>execute, "TestActivities::proc4", Void.class, "1", 2, 3, 4)
          .get();
      return "workflow";
    }
  }

  @Test
  public void testAsyncUntypedActivity() {
    startWorkerFor(TestAsyncUtypedActivityWorkflowImpl.class);
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
  }

  public static class TestAsyncUtypedActivity2WorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ActivityStub testActivities = Workflow.newUntypedActivityStub(newActivityOptions2());
      Promise<String> a = testActivities.executeAsync("TestActivities::activity", String.class);
      Promise<String> a1 =
          testActivities.executeAsync(
              "customActivity1", String.class, "1"); // name overridden in annotation
      Promise<String> a2 =
          testActivities.executeAsync("TestActivities::activity2", String.class, "1", 2);
      Promise<String> a3 =
          testActivities.executeAsync("TestActivities::activity3", String.class, "1", 2, 3);
      Promise<String> a4 =
          testActivities.executeAsync("TestActivities::activity4", String.class, "1", 2, 3, 4);
      Promise<String> a5 =
          testActivities.executeAsync("TestActivities::activity5", String.class, "1", 2, 3, 4, 5);
      Promise<String> a6 =
          testActivities.executeAsync(
              "TestActivities::activity6", String.class, "1", 2, 3, 4, 5, 6);
      assertEquals("activity", a.get());
      assertEquals("1", a1.get());
      assertEquals("12", a2.get());
      assertEquals("123", a3.get());
      assertEquals("1234", a4.get());
      assertEquals("12345", a5.get());
      assertEquals("123456", a6.get());

      testActivities.executeAsync("TestActivities::proc", Void.class).get();
      testActivities.executeAsync("TestActivities::proc1", Void.class, "1").get();
      testActivities.executeAsync("TestActivities::proc2", Void.class, "1", 2).get();
      testActivities.executeAsync("TestActivities::proc3", Void.class, "1", 2, 3).get();
      testActivities.executeAsync("TestActivities::proc4", Void.class, "1", 2, 3, 4).get();
      testActivities.executeAsync("TestActivities::proc5", Void.class, "1", 2, 3, 4, 5).get();
      testActivities.executeAsync("TestActivities::proc6", Void.class, "1", 2, 3, 4, 5, 6).get();
      return "workflow";
    }
  }

  @Test
  public void testAsyncUntyped2Activity() {
    startWorkerFor(TestAsyncUtypedActivity2WorkflowImpl.class);
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

    assertEquals("proc", stubP.query());
    assertEquals("1", stubP1.query());
    assertEquals("12", stubP2.query());
    assertEquals("123", stubP3.query());
    assertEquals("1234", stubP4.query());
    assertEquals("12345", stubP5.query());
    assertEquals("123456", stubP6.query());
  }

  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    startWorkerFor(TestMultiargsWorkflowsImpl.class);
    WorkflowOptions workflowOptions = newWorkflowOptionsBuilder(taskList).build();
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

    assertEquals("proc", stubP.query());
    assertEquals("1", stubP1.query());
    assertEquals("12", stubP2.query());
    assertEquals("123", stubP3.query());
    assertEquals("1234", stubP4.query());
    assertEquals("12345", stubP5.query());
    assertEquals("123456", stubP6.query());
  }

  public static class TestChildAsyncWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions workflowOptions =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      TestMultiargsWorkflowsFunc stubF =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc.class, workflowOptions);
      assertEquals("func", Async.function(stubF::func).get());
      TestMultiargsWorkflowsFunc1 stubF1 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc1.class, workflowOptions);
      assertEquals("1", Async.function(stubF1::func1, "1").get());
      TestMultiargsWorkflowsFunc2 stubF2 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc2.class, workflowOptions);
      assertEquals("12", Async.function(stubF2::func2, "1", 2).get());
      TestMultiargsWorkflowsFunc3 stubF3 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc3.class, workflowOptions);
      assertEquals("123", Async.function(stubF3::func3, "1", 2, 3).get());
      TestMultiargsWorkflowsFunc4 stubF4 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc4.class, workflowOptions);
      assertEquals("1234", Async.function(stubF4::func4, "1", 2, 3, 4).get());
      TestMultiargsWorkflowsFunc5 stubF5 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc5.class, workflowOptions);
      assertEquals("12345", Async.function(stubF5::func5, "1", 2, 3, 4, 5).get());
      TestMultiargsWorkflowsFunc6 stubF6 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsFunc6.class, workflowOptions);
      assertEquals("123456", Async.function(stubF6::func6, "1", 2, 3, 4, 5, 6).get());

      TestMultiargsWorkflowsProc stubP =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc.class, workflowOptions);
      Async.procedure(stubP::proc).get();
      TestMultiargsWorkflowsProc1 stubP1 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc1.class, workflowOptions);
      Async.procedure(stubP1::proc1, "1").get();
      TestMultiargsWorkflowsProc2 stubP2 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc2.class, workflowOptions);
      Async.procedure(stubP2::proc2, "1", 2).get();
      TestMultiargsWorkflowsProc3 stubP3 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc3.class, workflowOptions);
      Async.procedure(stubP3::proc3, "1", 2, 3).get();
      TestMultiargsWorkflowsProc4 stubP4 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc4.class, workflowOptions);
      Async.procedure(stubP4::proc4, "1", 2, 3, 4).get();
      TestMultiargsWorkflowsProc5 stubP5 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc5.class, workflowOptions);
      Async.procedure(stubP5::proc5, "1", 2, 3, 4, 5).get();
      TestMultiargsWorkflowsProc6 stubP6 =
          Workflow.newChildWorkflowStub(TestMultiargsWorkflowsProc6.class, workflowOptions);
      Async.procedure(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();
      return null;
    }
  }

  @Test
  public void testChildAsyncWorkflow() {
    startWorkerFor(TestChildAsyncWorkflow.class, TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskList));
  }

  // This workflow is designed specifically for testing some internal logic in Async.procedure
  // and ChildWorkflowStubImpl. See comments on testChildAsyncLambdaWorkflow for more details.
  public interface WaitOnSignalWorkflow {

    @WorkflowMethod()
    void execute();

    @SignalMethod
    void signal(String value);
  }

  public static class TestWaitOnSignalWorkflowImpl implements WaitOnSignalWorkflow {

    private final CompletablePromise<String> signal = Workflow.newPromise();

    @Override
    public void execute() {
      signal.get();
    }

    @Override
    public void signal(String value) {
      signal.complete(value);
    }
  }

  public static class TestChildAsyncLambdaWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions workflowOptions =
          new ChildWorkflowOptions.Builder()
              .setExecutionStartToCloseTimeout(Duration.ofSeconds(100))
              .setTaskStartToCloseTimeout(Duration.ofSeconds(60))
              .setTaskList(taskList)
              .build();

      WaitOnSignalWorkflow child =
          Workflow.newChildWorkflowStub(WaitOnSignalWorkflow.class, workflowOptions);
      Promise<Void> promise = Async.procedure(() -> child.execute());
      Promise<WorkflowExecution> executionPromise = Workflow.getWorkflowExecution(child);
      assertNotNull(executionPromise);
      WorkflowExecution execution = executionPromise.get();
      assertNotEquals("", execution.getWorkflowId());
      assertNotEquals("", execution.getRunId());
      child.signal("test");

      promise.get();
      return null;
    }
  }

  // The purpose of this test is to exercise the lambda execution logic inside Async.procedure(),
  // which executes on a different thread than workflow-main. This is different than executing
  // classes that implements the workflow method interface, which executes on the workflow main
  // thread.
  @Test
  public void testChildAsyncLambdaWorkflow() {
    startWorkerFor(TestChildAsyncLambdaWorkflow.class, TestWaitOnSignalWorkflowImpl.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskList));
  }

  public static class TestUntypedChildStubWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions workflowOptions =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc::func", workflowOptions);
      assertEquals("func", stubF.execute(String.class));
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", stubF1.execute(String.class, "1"));
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc2::func2", workflowOptions);
      assertEquals("12", stubF2.execute(String.class, "1", 2));
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc3::func3", workflowOptions);
      assertEquals("123", stubF3.execute(String.class, "1", 2, 3));
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc4::func4", workflowOptions);
      assertEquals("1234", stubF4.execute(String.class, "1", 2, 3, 4));
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc5::func5", workflowOptions);
      assertEquals("12345", stubF5.execute(String.class, "1", 2, 3, 4, 5));
      ChildWorkflowStub stubF6 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc6::func6", workflowOptions);
      assertEquals("123456", stubF6.execute(String.class, "1", 2, 3, 4, 5, 6));

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc::proc", workflowOptions);
      stubP.execute(Void.class);
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc1::proc1", workflowOptions);
      stubP1.execute(Void.class, "1");
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc2::proc2", workflowOptions);
      stubP2.execute(Void.class, "1", 2);
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc3::proc3", workflowOptions);
      stubP3.execute(Void.class, "1", 2, 3);
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc4::proc4", workflowOptions);
      stubP4.execute(Void.class, "1", 2, 3, 4);
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc5::proc5", workflowOptions);
      stubP5.execute(Void.class, "1", 2, 3, 4, 5);
      ChildWorkflowStub stubP6 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc6::proc6", workflowOptions);
      stubP6.execute(Void.class, "1", 2, 3, 4, 5, 6);
      return null;
    }
  }

  @Test
  public void testUntypedChildStubWorkflow() {
    startWorkerFor(TestUntypedChildStubWorkflow.class, TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskList));
  }

  public static class TestUntypedChildStubWorkflowAsync implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions workflowOptions =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc::func", workflowOptions);
      assertEquals("func", stubF.executeAsync(String.class).get());
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", stubF1.executeAsync(String.class, "1").get());
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc2::func2", workflowOptions);
      assertEquals("12", stubF2.executeAsync(String.class, "1", 2).get());
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc3::func3", workflowOptions);
      assertEquals("123", stubF3.executeAsync(String.class, "1", 2, 3).get());
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc4::func4", workflowOptions);
      assertEquals("1234", stubF4.executeAsync(String.class, "1", 2, 3, 4).get());
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc5::func5", workflowOptions);
      assertEquals("12345", stubF5.executeAsync(String.class, "1", 2, 3, 4, 5).get());
      ChildWorkflowStub stubF6 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc6::func6", workflowOptions);
      assertEquals("123456", stubF6.executeAsync(String.class, "1", 2, 3, 4, 5, 6).get());

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc::proc", workflowOptions);
      stubP.executeAsync(Void.class).get();
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc1::proc1", workflowOptions);
      stubP1.executeAsync(Void.class, "1").get();
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc2::proc2", workflowOptions);
      stubP2.executeAsync(Void.class, "1", 2).get();
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc3::proc3", workflowOptions);
      stubP3.executeAsync(Void.class, "1", 2, 3).get();
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc4::proc4", workflowOptions);
      stubP4.executeAsync(Void.class, "1", 2, 3, 4).get();
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc5::proc5", workflowOptions);
      stubP5.executeAsync(Void.class, "1", 2, 3, 4, 5).get();
      ChildWorkflowStub stubP6 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc6::proc6", workflowOptions);
      stubP6.executeAsync(Void.class, "1", 2, 3, 4, 5, 6).get();
      return null;
    }
  }

  @Test
  public void testUntypedChildStubWorkflowAsync() {
    startWorkerFor(TestUntypedChildStubWorkflowAsync.class, TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskList));
  }

  public static class TestUntypedChildStubWorkflowAsyncInvoke implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      ChildWorkflowOptions workflowOptions =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc::func", workflowOptions);
      assertEquals("func", Async.function(stubF::<String>execute, String.class).get());
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", Async.function(stubF1::<String>execute, String.class, "1").get());
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc2::func2", workflowOptions);
      assertEquals("12", Async.function(stubF2::<String>execute, String.class, "1", 2).get());
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc3::func3", workflowOptions);
      assertEquals("123", Async.function(stubF3::<String>execute, String.class, "1", 2, 3).get());
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc4::func4", workflowOptions);
      assertEquals(
          "1234", Async.function(stubF4::<String>execute, String.class, "1", 2, 3, 4).get());
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsFunc5::func5", workflowOptions);
      assertEquals(
          "12345", Async.function(stubF5::<String>execute, String.class, "1", 2, 3, 4, 5).get());

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc::proc", workflowOptions);
      Async.procedure(stubP::<Void>execute, Void.class).get();
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc1::proc1", workflowOptions);
      Async.procedure(stubP1::<Void>execute, Void.class, "1").get();
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc2::proc2", workflowOptions);
      Async.procedure(stubP2::<Void>execute, Void.class, "1", 2).get();
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc3::proc3", workflowOptions);
      Async.procedure(stubP3::<Void>execute, Void.class, "1", 2, 3).get();
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc4::proc4", workflowOptions);
      Async.procedure(stubP4::<Void>execute, Void.class, "1", 2, 3, 4).get();
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub(
              "TestMultiargsWorkflowsProc5::proc5", workflowOptions);
      Async.procedure(stubP5::<Void>execute, Void.class, "1", 2, 3, 4, 5).get();
      return null;
    }
  }

  @Test
  public void testUntypedChildStubWorkflowAsyncInvoke() {
    startWorkerFor(TestUntypedChildStubWorkflowAsyncInvoke.class, TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflow1 client = workflowClient.newWorkflowStub(TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskList));
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

    @Override
    public List<String> getTrace() {
      throw new UnsupportedOperationException("not implemented");
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
    if (useExternalService) {
      tracer.setExpected(
          "registerQuery getTrace", "newTimer PT0.7S", "newTimer PT1.3S", "newTimer PT10S");
    } else {
      tracer.setExpected(
          "registerQuery getTrace", "newTimer PT11M40S", "newTimer PT21M40S", "newTimer PT10H");
    }
  }

  private static final RetryOptions retryOptions =
      new RetryOptions.Builder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(1))
          .setExpiration(Duration.ofSeconds(2))
          .setBackoffCoefficient(1)
          .build();

  public static class TestAsyncRetryWorkflowImpl implements TestWorkflow2 {

    private final List<String> trace = new ArrayList<>();

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

    @Override
    public List<String> getTrace() {
      return trace;
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
    List<String> trace = client.getTrace();
    assertEquals(trace.toString(), 3, trace.size());
    assertEquals("started", trace.get(0));
    assertTrue(trace.get(1).startsWith("retry at "));
    assertTrue(trace.get(2).startsWith("retry at "));
  }

  public static class TestAsyncRetryOptionsChangeWorkflow implements TestWorkflow2 {

    private final List<String> trace = new ArrayList<>();

    @Override
    public String execute(boolean useExternalService) {
      RetryOptions retryOptions;
      if (Workflow.isReplaying()) {
        retryOptions =
            new RetryOptions.Builder()
                .setMinimumAttempts(1)
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .build();
      } else {
        retryOptions =
            new RetryOptions.Builder()
                .setMinimumAttempts(2)
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(2)
                .build();
      }

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

    @Override
    public List<String> getTrace() {
      return trace;
    }
  }

  /** @see DeterministicRunnerTest#testRetry() */
  @Test
  public void testAsyncRetryOptionsChange() {
    startWorkerFor(TestAsyncRetryOptionsChangeWorkflow.class);
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
    List<String> trace = client.getTrace();
    assertEquals(trace.toString(), 3, trace.size());
    assertEquals("started", trace.get(0));
    assertTrue(trace.get(1).startsWith("retry at "));
    assertTrue(trace.get(2).startsWith("retry at "));
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
      TestWorkflow1 child = Workflow.newChildWorkflowStub(TestWorkflow1.class, options);
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
  public void testSignal() {
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

          // Test getTrace through replay by a local worker.
          Worker queryWorker;
          if (useExternalService) {
            Worker.Factory workerFactory = new Worker.Factory(service, DOMAIN);
            queryWorker = workerFactory.newWorker(taskList);
          } else {
            queryWorker = testEnvironment.newWorker(taskList);
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
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    execution.set(WorkflowClient.start(client.get()::execute));
  }

  @Test
  public void testSignalUntyped() {
    startWorkerFor(TestSignalWorkflowImpl.class);
    String workflowType = QueryableWorkflow.class.getSimpleName() + "::execute";
    AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            workflowType, newWorkflowOptionsBuilder(taskList).build());
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
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

    private final ITestChild child1 = Workflow.newChildWorkflowStub(ITestChild.class);
    private final ITestNamedChild child2;

    public TestParentWorkflow() {
      ChildWorkflowOptions.Builder options = new ChildWorkflowOptions.Builder();
      options.setWorkflowId(child2Id);
      child2 = Workflow.newChildWorkflowStub(ITestNamedChild.class, options.build());
    }

    @Override
    public String execute(String taskList) {
      Promise<String> r1 = Async.function(child1::execute, "Hello ");
      String r2 = child2.execute("World!");
      assertEquals(child2Id, Workflow.getWorkflowExecution(child2).get().getWorkflowId());
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

  private static String childReexecuteId = UUID.randomUUID().toString();

  public interface WorkflowIdReusePolicyParent {

    @WorkflowMethod
    String execute(boolean parallel, WorkflowIdReusePolicy policy);
  }

  public static class TestChildReexecuteWorkflow implements WorkflowIdReusePolicyParent {

    public TestChildReexecuteWorkflow() {}

    @Override
    public String execute(boolean parallel, WorkflowIdReusePolicy policy) {
      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder()
              .setWorkflowId(childReexecuteId)
              .setWorkflowIdReusePolicy(policy)
              .build();

      ITestNamedChild child1 = Workflow.newChildWorkflowStub(ITestNamedChild.class, options);
      Promise<String> r1P = Async.function(child1::execute, "Hello ");
      String r1 = null;
      if (!parallel) {
        r1 = r1P.get();
      }
      ITestNamedChild child2 = Workflow.newChildWorkflowStub(ITestNamedChild.class, options);
      String r2 = child2.execute("World!");
      if (parallel) {
        r1 = r1P.get();
      }
      assertEquals(childReexecuteId, Workflow.getWorkflowExecution(child1).get().getWorkflowId());
      assertEquals(childReexecuteId, Workflow.getWorkflowExecution(child2).get().getWorkflowId());
      return r1 + r2;
    }
  }

  @Test
  public void testChildAlreadyRunning() {
    startWorkerFor(TestChildReexecuteWorkflow.class, TestNamedChild.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    WorkflowIdReusePolicyParent client =
        workflowClient.newWorkflowStub(WorkflowIdReusePolicyParent.class, options.build());
    try {
      client.execute(false, WorkflowIdReusePolicy.RejectDuplicate);
      fail("unreachable");
    } catch (WorkflowFailureException e) {
      assertTrue(e.getCause() instanceof StartChildWorkflowFailedException);
    }
  }

  @Test
  public void testChildStartTwice() {
    startWorkerFor(TestChildReexecuteWorkflow.class, TestNamedChild.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    WorkflowIdReusePolicyParent client =
        workflowClient.newWorkflowStub(WorkflowIdReusePolicyParent.class, options.build());
    try {
      client.execute(true, WorkflowIdReusePolicy.RejectDuplicate);
      fail("unreachable");
    } catch (WorkflowFailureException e) {
      assertTrue(e.getCause() instanceof StartChildWorkflowFailedException);
    }
  }

  @Test
  public void testChildReexecute() {
    startWorkerFor(TestChildReexecuteWorkflow.class, TestNamedChild.class);

    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(200));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    WorkflowIdReusePolicyParent client =
        workflowClient.newWorkflowStub(WorkflowIdReusePolicyParent.class, options.build());
    assertEquals("HELLO WORLD!", client.execute(false, WorkflowIdReusePolicy.AllowDuplicate));
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
      child = Workflow.newChildWorkflowStub(ITestChild.class, options);

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
    AtomicReference<String> capturedWorkflowType = new AtomicReference<>();
    WorkflowClientOptions clientOptions =
        new WorkflowClientOptions.Builder()
            .setInterceptors(
                new WorkflowClientInterceptorBase() {
                  @Override
                  public WorkflowStub newUntypedWorkflowStub(
                      String workflowType, WorkflowOptions options, WorkflowStub next) {
                    capturedWorkflowType.set(workflowType);
                    return next;
                  }
                })
            .build();
    WorkflowClient wc;
    if (useExternalService) {
      wc = WorkflowClient.newInstance(DOMAIN, clientOptions);
    } else {
      wc = testEnvironment.newWorkflowClient(clientOptions);
    }

    TestWorkflow1 client = wc.newWorkflowStub(TestWorkflow1.class, options.build());
    try {
      client.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailureException);
      assertTrue(e.getCause().getCause() instanceof UnsupportedOperationException);
      assertEquals("simulated failure", e.getCause().getCause().getMessage());
    }
    assertEquals("TestWorkflow1::execute", capturedWorkflowType.get());
    assertEquals(3, angryChildActivity.getInvocationCount());
  }

  public static class TestSignalExternalWorkflow implements TestWorkflowSignaled {

    private final SignalingChild child = Workflow.newChildWorkflowStub(SignalingChild.class);

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
          Workflow.newExternalWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      parent.signal1("World");
      return greeting;
    }
  }

  @Test
  public void testSignalExternalWorkflow() {
    startWorkerFor(TestSignalExternalWorkflow.class, SignalingChildImpl.class);
    WorkflowOptions.Builder options = new WorkflowOptions.Builder();
    options.setExecutionStartToCloseTimeout(Duration.ofSeconds(2000));
    options.setTaskStartToCloseTimeout(Duration.ofSeconds(60));
    options.setTaskList(taskList);
    TestWorkflowSignaled client =
        workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options.build());
    assertEquals("Hello World!", client.execute());
    tracer.setExpected(
        "executeChildWorkflow SignalingChild::execute",
        "signalExternalWorkflow " + UUID_REGEXP + " testSignal");
  }

  public static class TestUntypedSignalExternalWorkflow implements TestWorkflowSignaled {

    private final ChildWorkflowStub child =
        Workflow.newUntypedChildWorkflowStub("SignalingChild::execute");

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          child.executeAsync(String.class, "Hello", Workflow.getWorkflowInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal1(String arg) {
      fromSignal.complete(arg);
    }
  }

  public static class UntypedSignalingChildImpl implements SignalingChild {

    @Override
    public String execute(String greeting, String parentWorkflowID) {
      ExternalWorkflowStub parent = Workflow.newUntypedExternalWorkflowStub(parentWorkflowID);
      parent.signal("testSignal", "World");
      return greeting;
    }
  }

  @Test
  public void testUntypedSignalExternalWorkflow() {
    startWorkerFor(TestUntypedSignalExternalWorkflow.class, UntypedSignalingChildImpl.class);
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
          Workflow.newExternalWorkflowStub(TestWorkflowSignaled.class, parentExecution);
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
          Workflow.newExternalWorkflowStub(TestWorkflowSignaled.class, parentExecution);
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
      child = Workflow.newChildWorkflowStub(ITestChild.class, options);
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

    List<UUID> activityUUIDList(List<UUID> arg);
  }

  private static class TestActivitiesImpl implements TestActivities {

    final ActivityCompletionClient completionClient;
    final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
    final List<String> procResult = Collections.synchronizedList(new ArrayList<>());
    private final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(0, 100, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private TestActivitiesImpl(ActivityCompletionClient completionClient) {
      this.completionClient = completionClient;
    }

    void close() throws InterruptedException {
      executor.shutdownNow();
      executor.awaitTermination(1, TimeUnit.MINUTES);
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

    @Override
    public List<UUID> activityUUIDList(List<UUID> arg) {
      return arg;
    }
  }

  public interface ProcInvocationQueryable {

    @QueryMethod(name = "getTrace")
    String query();
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

  public interface TestMultiargsWorkflowsProc extends ProcInvocationQueryable {

    @WorkflowMethod
    void proc();
  }

  public interface TestMultiargsWorkflowsProc1 extends ProcInvocationQueryable {

    @WorkflowMethod
    void proc1(String input);
  }

  public interface TestMultiargsWorkflowsProc2 extends ProcInvocationQueryable {

    @WorkflowMethod
    void proc2(String a1, int a2);
  }

  public interface TestMultiargsWorkflowsProc3 extends ProcInvocationQueryable {

    @WorkflowMethod
    void proc3(String a1, int a2, int a3);
  }

  public interface TestMultiargsWorkflowsProc4 extends ProcInvocationQueryable {

    @WorkflowMethod
    void proc4(String a1, int a2, int a3, int a4);
  }

  public interface TestMultiargsWorkflowsProc5 extends ProcInvocationQueryable {

    @WorkflowMethod
    void proc5(String a1, int a2, int a3, int a4, int a5);
  }

  public interface TestMultiargsWorkflowsProc6 extends ProcInvocationQueryable {

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

    private String procResult;

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
      procResult = ("proc");
    }

    @Override
    public void proc1(String a1) {
      procResult = (a1);
    }

    @Override
    public void proc2(String a1, int a2) {
      procResult = (a1 + a2);
    }

    @Override
    public void proc3(String a1, int a2, int a3) {
      procResult = (a1 + a2 + a3);
    }

    @Override
    public void proc4(String a1, int a2, int a3, int a4) {
      procResult = (a1 + a2 + a3 + a4);
    }

    @Override
    public void proc5(String a1, int a2, int a3, int a4, int a5) {
      procResult = (a1 + a2 + a3 + a4 + a5);
    }

    @Override
    public void proc6(String a1, int a2, int a3, int a4, int a5, int a6) {
      procResult = (a1 + a2 + a3 + a4 + a5 + a6);
    }

    @Override
    public String query() {
      return procResult;
    }
  }

  public static class TestWorkflowLocals implements TestWorkflow1 {

    private final WorkflowThreadLocal<Integer> threadLocal =
        WorkflowThreadLocal.withInitial(() -> 2);

    private final WorkflowLocal<Integer> workflowLocal = WorkflowLocal.withInitial(() -> 5);

    @Override
    public String execute(String taskList) {
      assertEquals(2, (int) threadLocal.get());
      assertEquals(5, (int) workflowLocal.get());
      Promise<Void> p1 =
          Async.procedure(
              () -> {
                assertEquals(2, (int) threadLocal.get());
                threadLocal.set(10);
                Workflow.sleep(Duration.ofSeconds(1));
                assertEquals(10, (int) threadLocal.get());
                assertEquals(100, (int) workflowLocal.get());
              });
      Promise<Void> p2 =
          Async.procedure(
              () -> {
                assertEquals(2, (int) threadLocal.get());
                threadLocal.set(22);
                workflowLocal.set(100);
                assertEquals(22, (int) threadLocal.get());
              });
      p1.get();
      p2.get();
      return "result=" + threadLocal.get() + ", " + workflowLocal.get();
    }
  }

  @Test
  public void testWorkflowLocals() {
    startWorkerFor(TestWorkflowLocals.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("result=2, 100", result);
  }

  public static class TestSideEffectWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));

      long workflowTime = Workflow.currentTimeMillis();
      long time = Workflow.sideEffect(long.class, () -> workflowTime);
      Workflow.sleep(Duration.ofSeconds(1));
      String result;
      if (workflowTime == time) {
        result = testActivities.activity1("activity1");
      } else {
        result = testActivities.activity2("activity2", 2);
      }
      return result;
    }
  }

  @Test
  public void testSideEffect() {
    startWorkerFor(TestSideEffectWorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("activity1", result);
    tracer.setExpected("sideEffect", "sleep PT1S", "executeActivity customActivity1");
  }

  private static final Map<String, Queue<Long>> mutableSideEffectValue =
      Collections.synchronizedMap(new HashMap<>());

  public static class TestMutableSideEffectWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 4; i++) {
        long value =
            Workflow.mutableSideEffect(
                "id1",
                Long.class,
                (o, n) -> n > o,
                () -> mutableSideEffectValue.get(taskList).poll());
        if (result.length() > 0) {
          result.append(", ");
        }
        result.append(value);
        // Sleep is here to ensure that mutableSideEffect works when replaying a history.
        if (i >= 3) {
          Workflow.sleep(Duration.ofSeconds(1));
        }
      }
      return result.toString();
    }
  }

  @Test
  public void testMutableSideEffect() {
    startWorkerFor(TestMutableSideEffectWorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    ArrayDeque<Long> values = new ArrayDeque<>();
    values.add(1234L);
    values.add(1234L);
    values.add(123L); // expected to be ignored as it is smaller than 1234.
    values.add(3456L);
    mutableSideEffectValue.put(taskList, values);
    String result = workflowStub.execute(taskList);
    assertEquals("1234, 1234, 1234, 3456", result);
  }

  private static final Set<String> getVersionExecuted =
      Collections.synchronizedSet(new HashSet<>());

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      String result = "";
      if (version == Workflow.DEFAULT_VERSION) {
        result += testActivities.activity1("activity1");
      } else {
        result += testActivities.activity2("activity2", 2); // This is executed.
      }

      // Test version change in non-replay code.
      version = Workflow.getVersion("test_change", 1, 2);
      if (version == 1) {
        result += testActivities.activity1("activity1"); // This is executed.
      } else {
        result += testActivities.activity2("activity2", 2);
      }

      // Test adding a version check in replay code.
      if (!getVersionExecuted.contains(taskList + "-test_change_2")) {
        result += testActivities.activity1("activity1"); // This is executed in non-replay mode.
        getVersionExecuted.add(taskList + "-test_change_2");
      } else {
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 1);
        if (version2 == Workflow.DEFAULT_VERSION) {
          result += testActivities.activity1("activity1"); // This is executed in replay mode.
        } else {
          result += testActivities.activity2("activity2", 2);
        }
      }

      // Test get version in replay mode.
      Workflow.sleep(1000);
      version = Workflow.getVersion("test_change", 1, 2);
      if (version == 1) {
        result += testActivities.activity1("activity1"); // This is executed.
      } else {
        result += testActivities.activity2("activity2", 2);
      }

      return result;
    }
  }

  @Test
  public void testGetVersion() {
    startWorkerFor(TestGetVersionWorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("activity22activity1activity1activity1", result);
    tracer.setExpected(
        "getVersion",
        "executeActivity TestActivities::activity2",
        "getVersion",
        "executeActivity customActivity1",
        "executeActivity customActivity1",
        "sleep PT1S",
        "getVersion",
        "executeActivity customActivity1");
  }

  // The following test covers the scenario where getVersion call is removed before a
  // non-version-marker decision.
  public static class TestGetVersionRemovedInReplayWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));
      String result;
      // Test removing a version check in replay code.
      if (!getVersionExecuted.contains(taskList)) {
        int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
        if (version == Workflow.DEFAULT_VERSION) {
          result = testActivities.activity1("activity1");
        } else {
          result = testActivities.activity2("activity2", 2); // This is executed in non-replay mode.
        }
        getVersionExecuted.add(taskList);
      } else {
        result = testActivities.activity2("activity2", 2);
      }

      result += testActivities.activity();
      return result;
    }
  }

  @Test
  public void testGetVersionRemovedInReplay() {
    startWorkerFor(TestGetVersionRemovedInReplayWorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("activity22activity", result);
    tracer.setExpected(
        "getVersion",
        "executeActivity TestActivities::activity2",
        "executeActivity TestActivities::activity");
  }

  // The following test covers the scenario where getVersion call is removed before another
  // version-marker decision.
  public static class TestGetVersionRemovedInReplay2WorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));
      // Test removing a version check in replay code.
      if (!getVersionExecuted.contains(taskList)) {
        Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
        Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 2);
        getVersionExecuted.add(taskList);
      } else {
        Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 2);
      }

      return testActivities.activity();
    }
  }

  @Test
  public void testGetVersionRemovedInReplay2() {
    startWorkerFor(TestGetVersionRemovedInReplay2WorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("activity", result);
    tracer.setExpected("getVersion", "getVersion", "executeActivity TestActivities::activity");
  }

  public static class TestVersionNotSupportedWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities testActivities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      String result = "";
      if (version == Workflow.DEFAULT_VERSION) {
        result += testActivities.activity1("activity1");
      } else {
        result += testActivities.activity2("activity2", 2); // This is executed.
      }

      // Catching error from getVersion is only for unit test purpose.
      // Do not ever do it in production code.
      try {
        Workflow.getVersion("test_change", 2, 3);
      } catch (Error e) {
        throw Workflow.wrap(new Exception("unsupported change version"));
      }
      return result;
    }
  }

  @Test
  public void testVersionNotSupported() {
    startWorkerFor(TestVersionNotSupportedWorkflowImpl.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());

    try {
      workflowStub.execute(taskList);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertEquals("unsupported change version", e.getCause().getMessage());
    }
  }

  private static class TracingWorkflowInterceptorFactory
      implements Function<WorkflowInterceptor, WorkflowInterceptor> {

    private final FilteredTrace trace = new FilteredTrace();
    private List<String> expected;

    @Override
    public WorkflowInterceptor apply(WorkflowInterceptor next) {
      return new TracingWorkflowInterceptor(trace, next);
    }

    public String getTrace() {
      return String.join("\n", trace.getImpl());
    }

    public void setExpected(String... expected) {
      this.expected = Arrays.asList(expected);
    }

    public void assertExpected() {
      if (expected != null) {
        List<String> traceElements = trace.getImpl();
        for (int i = 0; i < traceElements.size(); i++) {
          String t = traceElements.get(i);
          String expectedRegExp = expected.get(i);
          Assert.assertTrue(t + " doesn't match " + expectedRegExp, t.matches(expectedRegExp));
        }
      }
    }
  }

  public static class TestUUIDAndRandom implements TestWorkflow1 {

    @Override
    public String execute(String taskList) {
      TestActivities activities =
          Workflow.newActivityStub(TestActivities.class, newActivityOptions1(taskList));
      Random rand1 = Workflow.newRandom();
      int r11 = rand1.nextInt();
      int r12 = r11 + rand1.nextInt();
      int savedInt = Workflow.sideEffect(int.class, () -> r12);
      String id = Workflow.randomUUID().toString() + "-" + Workflow.randomUUID().toString();
      String savedId = Workflow.sideEffect(String.class, () -> id);
      // Invoke activity in a blocking mode to ensure that asserts run after replay.
      String result = activities.activity2("foo", 10);
      // Assert that during replay values didn't change.
      assertEquals(savedId, id);
      assertEquals(savedInt, r12);
      return result;
    }
  }

  @Test
  public void testUUIDAndRandom() {
    startWorkerFor(TestUUIDAndRandom.class);
    TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflow1.class, newWorkflowOptionsBuilder(taskList).build());
    String result = workflowStub.execute(taskList);
    assertEquals("foo10", result);
    tracer.setExpected("sideEffect", "sideEffect", "executeActivity TestActivities::activity2");
  }

  public interface GenericParametersActivity {

    List<UUID> execute(List<UUID> arg1, Set<UUID> arg2);
  }

  public static class GenericParametersActivityImpl implements GenericParametersActivity {

    @Override
    public List<UUID> execute(List<UUID> arg1, Set<UUID> arg2) {
      List<UUID> result = new ArrayList<>();
      result.addAll(arg1);
      result.addAll(arg2);
      return result;
    }
  }

  public interface GenericParametersWorkflow {

    @WorkflowMethod
    List<UUID> execute(String taskList, List<UUID> arg1, Set<UUID> arg2);

    @SignalMethod
    void signal(List<UUID> arg);

    @QueryMethod
    List<UUID> query(List<UUID> arg);
  }

  public static class GenericParametersWorkflowImpl implements GenericParametersWorkflow {

    private List<UUID> signaled;
    private GenericParametersActivity activity;

    @Override
    public List<UUID> execute(String taskList, List<UUID> arg1, Set<UUID> arg2) {
      Workflow.await(() -> signaled != null && signaled.size() == 0);
      activity =
          Workflow.newActivityStub(GenericParametersActivity.class, newActivityOptions1(taskList));
      return activity.execute(arg1, arg2);
    }

    @Override
    public void signal(List<UUID> arg) {
      signaled = arg;
    }

    @Override
    public List<UUID> query(List<UUID> arg) {
      List<UUID> result = new ArrayList<>();
      result.addAll(arg);
      result.addAll(signaled);
      return result;
    }
  }

  @Test
  public void testGenericParametersWorkflow() throws ExecutionException, InterruptedException {
    worker.registerActivitiesImplementations(new GenericParametersActivityImpl());
    startWorkerFor(GenericParametersWorkflowImpl.class);
    GenericParametersWorkflow workflowStub =
        workflowClient.newWorkflowStub(
            GenericParametersWorkflow.class, newWorkflowOptionsBuilder(taskList).build());
    List<UUID> uuidList = new ArrayList<>();
    uuidList.add(UUID.randomUUID());
    uuidList.add(UUID.randomUUID());
    Set<UUID> uuidSet = new HashSet<>();
    uuidSet.add(UUID.randomUUID());
    uuidSet.add(UUID.randomUUID());
    uuidSet.add(UUID.randomUUID());
    CompletableFuture<List<UUID>> resultF =
        WorkflowClient.execute(workflowStub::execute, taskList, uuidList, uuidSet);
    // Test signal and query serialization
    workflowStub.signal(uuidList);
    if (useExternalService) {
      Thread.sleep(10000);
    } else {
      testEnvironment.sleep(Duration.ofSeconds(1));
    }
    List<UUID> queryArg = new ArrayList<>();
    queryArg.add(UUID.randomUUID());
    queryArg.add(UUID.randomUUID());
    List<UUID> queryResult = workflowStub.query(queryArg);
    List<UUID> expectedQueryResult = new ArrayList<>();
    expectedQueryResult.addAll(queryArg);
    expectedQueryResult.addAll(uuidList);
    expectedQueryResult.sort(UUID::compareTo);
    queryResult.sort(UUID::compareTo);
    assertEquals(expectedQueryResult, queryResult);
    workflowStub.signal(new ArrayList<>()); // empty list unblocks workflow await.
    // test workflow result serialization
    List<UUID> expectedResult = new ArrayList<>();
    expectedResult.addAll(uuidList);
    expectedResult.addAll(uuidSet);
    List<UUID> result = resultF.get();
    result.sort(UUID::compareTo);
    expectedResult.sort(UUID::compareTo);
    assertEquals(expectedResult, result);
  }

  private static class FilteredTrace {

    private final List<String> impl = Collections.synchronizedList(new ArrayList<>());

    public boolean add(String s) {
      log.trace("FilteredTrace isReplaying=" + Workflow.isReplaying());
      if (!Workflow.isReplaying()) {
        return impl.add(s);
      }
      return true;
    }

    public List<String> getImpl() {
      return impl;
    }
  }

  private static class TracingWorkflowInterceptor implements WorkflowInterceptor {

    private final FilteredTrace trace;
    private final WorkflowInterceptor next;

    private TracingWorkflowInterceptor(FilteredTrace trace, WorkflowInterceptor next) {
      this.trace = trace;
      this.next = Objects.requireNonNull(next);
    }

    @Override
    public <R> Promise<R> executeActivity(
        String activityName,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        ActivityOptions options) {
      trace.add("executeActivity " + activityName);
      return next.executeActivity(activityName, resultClass, resultType, args, options);
    }

    @Override
    public <R> WorkflowResult<R> executeChildWorkflow(
        String workflowType,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        ChildWorkflowOptions options) {
      trace.add("executeChildWorkflow " + workflowType);
      return next.executeChildWorkflow(workflowType, resultClass, resultType, args, options);
    }

    @Override
    public Random newRandom() {
      trace.add("newRandom");
      return next.newRandom();
    }

    @Override
    public Promise<Void> signalExternalWorkflow(
        WorkflowExecution execution, String signalName, Object[] args) {
      trace.add("signalExternalWorkflow " + execution.getWorkflowId() + " " + signalName);
      return next.signalExternalWorkflow(execution, signalName, args);
    }

    @Override
    public Promise<Void> cancelWorkflow(WorkflowExecution execution) {
      trace.add("cancelWorkflow " + execution.getWorkflowId());
      return next.cancelWorkflow(execution);
    }

    @Override
    public void sleep(Duration duration) {
      trace.add("sleep " + duration);
      next.sleep(duration);
    }

    @Override
    public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
      trace.add("await " + timeout + " " + reason);
      return next.await(timeout, reason, unblockCondition);
    }

    @Override
    public void await(String reason, Supplier<Boolean> unblockCondition) {
      trace.add("await " + reason);
      next.await(reason, unblockCondition);
    }

    @Override
    public Promise<Void> newTimer(Duration duration) {
      trace.add("newTimer " + duration);
      return next.newTimer(duration);
    }

    @Override
    public <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
      trace.add("sideEffect");
      return next.sideEffect(resultClass, resultType, func);
    }

    @Override
    public <R> R mutableSideEffect(
        String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
      trace.add("mutableSideEffect");
      return next.mutableSideEffect(id, resultClass, resultType, updated, func);
    }

    @Override
    public int getVersion(String changeID, int minSupported, int maxSupported) {
      trace.add("getVersion");
      return next.getVersion(changeID, minSupported, maxSupported);
    }

    @Override
    public void continueAsNew(
        Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object[] args) {
      trace.add("continueAsNew");
      next.continueAsNew(workflowType, options, args);
    }

    @Override
    public void registerQuery(String queryType, Type[] argTypes, Func1<Object[], Object> callback) {
      trace.add("registerQuery " + queryType);
      next.registerQuery(queryType, argTypes, callback);
    }

    @Override
    public UUID randomUUID() {
      trace.add("randomUUID");
      return next.randomUUID();
    }
  }
}
