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

package io.temporal.workflow;

import static io.temporal.client.WorkflowClient.QUERY_TYPE_STACK_TRACE;
import static org.junit.Assert.*;

import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.activity.*;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.*;
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GsonJsonPayloadConverter;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.failure.*;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.replay.InternalWorkflowTaskException;
import io.temporal.internal.sync.DeterministicRunnerTest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.*;
import io.temporal.worker.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowTest.class);
  private static final String serviceAddress = System.getenv("TEMPORAL_SERVICE_ADDRESS");
  private static WorkflowServiceStubs service;
  private static TestWorkflowEnvironment testEnvironment;
  private final List<ScheduledFuture<?>> delayedCallbacks = new ArrayList<>();
  private final AtomicReference<String> lastStartedWorkflowType = new AtomicReference<>();
  private String taskQueue;
  private Worker worker;
  private TestActivities.TestActivitiesImpl activitiesImpl;
  private WorkflowClient workflowClient;
  private TracingWorkerInterceptor tracer;
  private WorkerFactory workerFactory;
  private ScheduledExecutorService scheduledExecutor;

  @Rule public TestName testName = new TestName();

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

  @BeforeClass()
  public static void startService() {
    if (SDKTestWorkflowRule.REGENERATE_JSON_FILES && !SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      throw new IllegalStateException(
          "SDKTestWorkflowRule.REGENERATE_JSON_FILES is true when SDKTestWorkflowRule.USE_EXTERNAL_SERVICE is false");
    }
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      service =
          WorkflowServiceStubs.newInstance(
              WorkflowServiceStubsOptions.newBuilder().setTarget(serviceAddress).build());
    }
  }

  @AfterClass
  public static void closeService() {
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      service.shutdownNow();
      service.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Before
  public void setUp() {
    String testMethod = testName.getMethodName();
    if (testMethod.startsWith("testExecute") || testMethod.startsWith("testStart")) {
      taskQueue = SDKTestWorkflowRule.ANNOTATION_TASK_QUEUE;
    } else {
      taskQueue = "WorkflowTest-" + testMethod + "-" + UUID.randomUUID().toString();
    }
    TracingWorkerInterceptor.FilteredTrace trace = new TracingWorkerInterceptor.FilteredTrace();
    tracer = new TracingWorkerInterceptor(trace);
    // TODO: Create a version of TestWorkflowEnvironment that runs against a real service.
    lastStartedWorkflowType.set(null);
    WorkflowClientOptions workflowClientOptions =
        WorkflowClientOptions.newBuilder()
            .setBinaryChecksum(SDKTestWorkflowRule.BINARY_CHECKSUM)
            .setInterceptors(
                new WorkflowClientInterceptorBase() {
                  @Override
                  public WorkflowStub newUntypedWorkflowStub(
                      String workflowType, WorkflowOptions options, WorkflowStub next) {
                    lastStartedWorkflowType.set(workflowType);
                    return next;
                  }
                })
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .build();
    boolean versionTest = testMethod.contains("GetVersion") || testMethod.contains("Deterministic");
    WorkerFactoryOptions factoryOptions =
        WorkerFactoryOptions.newBuilder()
            .setWorkerInterceptors(tracer)
            .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
            .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(
                versionTest ? Duration.ZERO : Duration.ofSeconds(10))
            .build();
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workflowClient = WorkflowClient.newInstance(service, workflowClientOptions);
      workerFactory = WorkerFactory.newInstance(workflowClient, factoryOptions);
      WorkerOptions workerOptions =
          WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(1000).build();

      worker = workerFactory.newWorker(taskQueue, workerOptions);
      scheduledExecutor = new ScheduledThreadPoolExecutor(1);
    } else {
      TestEnvironmentOptions testOptions =
          TestEnvironmentOptions.newBuilder()
              .setWorkflowClientOptions(workflowClientOptions)
              .setWorkerFactoryOptions(factoryOptions)
              .build();
      testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
      worker = testEnvironment.newWorker(taskQueue);
      workflowClient = testEnvironment.getWorkflowClient();
      service = testEnvironment.getWorkflowService();
    }

    ActivityCompletionClient completionClient = workflowClient.newActivityCompletionClient();
    activitiesImpl = new TestActivities.TestActivitiesImpl(completionClient);
    worker.registerActivitiesImplementations(activitiesImpl);

    TestOptions.newWorkflowOptionsBuilder(taskQueue);

    TestOptions.newActivityOptionsForTaskQueue(taskQueue);
    activitiesImpl.invocations.clear();
    activitiesImpl.procResult.clear();
  }

  @After
  public void tearDown() throws Throwable {
    if (activitiesImpl != null) {
      activitiesImpl.close();
    }
    if (testEnvironment != null) {
      testEnvironment.close();
    } else {
      workerFactory.shutdown();
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

  private void startWorkerFor(
      WorkflowImplementationOptions implementationOptions, Class<?>... workflowTypes) {
    worker.registerWorkflowImplementationTypes(implementationOptions, workflowTypes);
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workerFactory.start();
    } else {
      testEnvironment.start();
    }
  }

  private void startWorkerFor(Class<?>... workflowTypes) {
    worker.registerWorkflowImplementationTypes(workflowTypes);
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workerFactory.start();
    } else {
      testEnvironment.start();
    }
  }

  // TODO: Refactor testEnvironment to support testing through real service to avoid this
  // conditional switches
  void registerDelayedCallback(Duration delay, Runnable r) {
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      ScheduledFuture<?> result =
          scheduledExecutor.schedule(r, delay.toMillis(), TimeUnit.MILLISECONDS);
      delayedCallbacks.add(result);
    } else {
      testEnvironment.registerDelayedCallback(delay, r);
    }
  }

  void sleep(Duration d) {
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      try {
        Thread.sleep(d.toMillis());
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted", e);
      }
    } else {
      testEnvironment.sleep(d);
    }
  }

  long currentTimeMillis() {
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      return System.currentTimeMillis();
    } else {
      return testEnvironment.currentTimeMillis();
    }
  }

  @WorkflowInterface
  public interface TestWorkflowSignaled {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal1(String arg);
  }

  public static class TestSyncWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities activities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
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
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("activity10", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "newThread null",
        "sleep PT2S",
        "executeActivity ActivityWithDelay",
        "activity ActivityWithDelay",
        "executeActivity Activity2",
        "activity Activity2");
  }

  @WorkflowInterface
  public interface TestMultipleTimers {
    @WorkflowMethod
    long execute();
  }

  public static class TestMultipleTimersImpl implements TestMultipleTimers {

    @Override
    public long execute() {
      Promise<Void> t1 = Async.procedure(() -> Workflow.sleep(Duration.ofSeconds(1)));
      Promise<Void> t2 = Async.procedure(() -> Workflow.sleep(Duration.ofSeconds(2)));
      long start = Workflow.currentTimeMillis();
      Promise.anyOf(t1, t2).get();
      long elapsed = Workflow.currentTimeMillis() - start;
      return elapsed;
    }
  }

  @Test
  public void testMultipleTimers() {
    startWorkerFor(TestMultipleTimersImpl.class);
    TestMultipleTimers workflowStub =
        workflowClient.newWorkflowStub(
            TestMultipleTimers.class, TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    long result = workflowStub.execute();
    assertTrue("should be around 1 second: " + result, result < 2000);
  }

  interface EmptyInterface {}

  interface UnrelatedInterface {
    void unrelatedMethod();
  }

  public static class TestHeartbeatTimeoutDetails implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setHeartbeatTimeout(Duration.ofSeconds(1)) // short heartbeat timeout;
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();

      TestActivities activities = Workflow.newActivityStub(TestActivities.class, options);
      try {
        // false for second argument means to heartbeat once to set details and then stop.
        activities.activityWithDelay(5000, false);
      } catch (ActivityFailure e) {
        TimeoutFailure te = (TimeoutFailure) e.getCause();
        log.info("TestHeartbeatTimeoutDetails expected timeout", e);
        assertEquals(TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, te.getTimeoutType());
        assertTrue(te.getCause() instanceof TimeoutFailure);
        assertEquals(
            TimeoutType.TIMEOUT_TYPE_HEARTBEAT, ((TimeoutFailure) te.getCause()).getTimeoutType());
        return (te.getLastHeartbeatDetails().get(String.class));
      }
      throw new RuntimeException("unreachable");
    }
  }

  @Test
  public void testHeartbeatTimeoutDetails() {
    startWorkerFor(TestHeartbeatTimeoutDetails.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("heartbeatValue", result);
  }

  @Test
  public void testSyncUntypedAndStackTrace() {
    startWorkerFor(TestSyncWorkflowImpl.class);
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution = workflowStub.start(taskQueue);
    sleep(Duration.ofMillis(500));
    String stackTrace = workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class);
    assertTrue(stackTrace, stackTrace.contains("WorkflowTest$TestSyncWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
    // Test stub created from workflow execution.
    workflowStub = workflowClient.newUntypedWorkflowStub(execution, workflowStub.getWorkflowType());
    stackTrace = workflowStub.query(QUERY_TYPE_STACK_TRACE, String.class);
    assertTrue(stackTrace, stackTrace.contains("WorkflowTest$TestSyncWorkflowImpl.execute"));
    assertTrue(stackTrace, stackTrace.contains("activityWithDelay"));
    String result = workflowStub.getResult(String.class);
    assertEquals("activity10", result);
  }

  public static class TestCancellationForWorkflowsWithFailedPromises
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Async.function(
          () -> {
            throw new UncheckedExecutionException(new Exception("Oh noo!"));
          });
      Async.function(
          () -> {
            throw new UncheckedExecutionException(new Exception("Oh noo again!"));
          });
      Workflow.await(() -> false);
      fail("unreachable");
      return "done";
    }
  }

  @Test
  public void workflowsWithFailedPromisesCanBeCanceled() {
    startWorkerFor(TestCancellationForWorkflowsWithFailedPromises.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    client.start(taskQueue);
    client.cancel();

    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  @Test
  public void testWorkflowCancellation() {
    startWorkerFor(TestSyncWorkflowImpl.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    client.start(taskQueue);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  @Test
  public void testWorkflowTermination() throws InterruptedException {
    startWorkerFor(TestSyncWorkflowImpl.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    client.start(taskQueue);
    Thread.sleep(1000);
    client.terminate("boo", "detail1", "detail2");
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException ignored) {
      assertTrue(ignored.getCause() instanceof TerminatedFailure);
      assertEquals("boo", ((TerminatedFailure) ignored.getCause()).getOriginalMessage());
    }
  }

  public static class SleepyChild implements TestChildWorkflow {

    @Override
    public void execute() {
      Workflow.await(() -> false);
    }
  }

  public static class ParentThatStartsChildInCancellationScope implements TestWorkflow {

    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      TestChildWorkflow child =
          Workflow.newChildWorkflowStub(
              TestChildWorkflow.class,
              ChildWorkflowOptions.newBuilder().setCancellationType(cancellationType).build());
      List<Promise<Void>> children = new ArrayList<>();
      // This is a non blocking call that returns immediately.
      // Use child.composeGreeting("Hello", name) to call synchronously.
      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> {
                Promise<Void> promise = Async.procedure(child::execute);
                children.add(promise);
              });
      scope.run();
      Promise.allOf(children).get();
    }
  }

  @Test
  public void testStartChildWorkflowWithCancellationScopeAndCancelParent() {
    startWorkerFor(ParentThatStartsChildInCancellationScope.class, SleepyChild.class);
    WorkflowStub workflow =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    workflow.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    workflow.cancel();
    try {
      workflow.getResult(Void.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class TestCancellationScopePromise implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
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
            "TestWorkflow1", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    client.start(taskQueue);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class TestDetachedCancellationScope implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      try {
        testActivities.activityWithDelay(100000, true);
        fail("unreachable");
      } catch (ActivityFailure e) {
        assertTrue(e.getCause() instanceof CanceledFailure);
        Workflow.newDetachedCancellationScope(() -> assertEquals(1, testActivities.activity1(1)))
            .run();
      }
      try {
        Workflow.sleep(Duration.ofHours(1));
        fail("unreachable");
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> assertEquals("a12", testActivities.activity2("a1", 2)))
            .run();
      }
      try {
        Workflow.newTimer(Duration.ofHours(1)).get();
        fail("unreachable");
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> assertEquals("a123", testActivities.activity3("a1", 2, 3)))
            .run();
      }
      return "result";
    }
  }

  @Test
  public void testDetachedScope() {
    startWorkerFor(TestDetachedCancellationScope.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    client.start(taskQueue);
    sleep(Duration.ofMillis(500)); // To let activityWithDelay start.
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    activitiesImpl.assertInvocations("activityWithDelay", "activity1", "activity2", "activity3");
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute(ChildWorkflowCancellationType cancellationType);
  }

  public static class TestParentWorkflowImpl implements TestWorkflow {

    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      TestChildWorkflow child =
          Workflow.newChildWorkflowStub(
              TestChildWorkflow.class,
              ChildWorkflowOptions.newBuilder().setCancellationType(cancellationType).build());
      child.execute();
    }
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestChildWorkflowImpl implements TestChildWorkflow {
    @Override
    public void execute() {
      try {
        Workflow.sleep(Duration.ofHours(1));
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(() -> Workflow.sleep(Duration.ofSeconds(1))).run();
      }
    }
  }

  public static class SimpleTestWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class,
              ActivityOptions.newBuilder(TestOptions.newActivityOptionsForTaskQueue(taskQueue))
                  .build());
      testActivities.activity();
      return "done";
    }
  }

  @Test
  public void testBinaryChecksumSetWhenTaskCompleted() {
    startWorkerFor(SimpleTestWorkflow.class);
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution = WorkflowClient.start(client::execute, taskQueue);
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    SDKTestWorkflowRule.waitForOKQuery(stub);
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    boolean foundCompletedTask = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
        assertEquals(
            SDKTestWorkflowRule.BINARY_CHECKSUM,
            event.getWorkflowTaskCompletedEventAttributes().getBinaryChecksum());
        foundCompletedTask = true;
      }
    }
    assertTrue(foundCompletedTask);
  }

  @Test
  public void testChildWorkflowWaitCancellationRequested() {
    startWorkerFor(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution =
        client.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED);
    SDKTestWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    boolean hasChildCanceled = false;
    boolean hasChildCancelRequested = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
        hasChildCanceled = true;
      }
      if (event.getEventType()
          == EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
        hasChildCancelRequested = true;
      }
    }
    assertTrue(hasChildCancelRequested);
    assertFalse(hasChildCanceled);
  }

  @Test
  public void testChildWorkflowWaitCancellationCompleted() {
    startWorkerFor(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution =
        client.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    SDKTestWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    boolean hasChildCanceled = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
        hasChildCanceled = true;
      }
    }
    assertTrue(hasChildCanceled);
  }

  @Test
  public void testChildWorkflowCancellationAbandon() {
    startWorkerFor(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.ABANDON);
    SDKTestWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    boolean hasChildCancelInitiated = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType()
          == EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
        hasChildCancelInitiated = true;
      }
    }
    assertFalse(hasChildCancelInitiated);
  }

  @Test
  public void testChildWorkflowCancellationTryCancel() {
    startWorkerFor(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class);
    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow", TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution = client.start(ChildWorkflowCancellationType.TRY_CANCEL);
    SDKTestWorkflowRule.waitForOKQuery(client);
    client.cancel();
    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    boolean hasChildCancelInitiated = false;
    boolean hasChildCancelRequested = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType()
          == EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
        hasChildCancelInitiated = true;
      }
      if (event.getEventType()
          == EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
        hasChildCancelRequested = true;
      }
    }
    assertTrue(hasChildCancelInitiated);
    assertFalse(hasChildCancelRequested);
  }

  @WorkflowInterface
  public interface TestContinueAsNew {

    @WorkflowMethod
    int execute(int count, String continueAsNewTaskQueue);
  }

  public static class TestContinueAsNewImpl implements TestContinueAsNew {

    @Override
    public int execute(int count, String continueAsNewTaskQueue) {
      String taskQueue = Workflow.getInfo().getTaskQueue();
      if (count == 0) {
        assertEquals(continueAsNewTaskQueue, taskQueue);
        return 111;
      }
      Map<String, Object> memo = new HashMap<>();
      memo.put("myKey", "MyValue");
      Map<String, Object> searchAttributes = new HashMap<>();
      searchAttributes.put("CustomKeywordField", "foo1");
      ContinueAsNewOptions options =
          ContinueAsNewOptions.newBuilder()
              .setTaskQueue(continueAsNewTaskQueue)
              .setMemo(memo)
              .setSearchAttributes(searchAttributes)
              .build();
      TestContinueAsNew next = Workflow.newContinueAsNewStub(TestContinueAsNew.class, options);
      next.execute(count - 1, continueAsNewTaskQueue);
      throw new RuntimeException("unreachable");
    }
  }

  @Test
  public void testContinueAsNew() {
    Worker w2;
    String continuedTaskQueue = this.taskQueue + "_continued";
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      w2 = workerFactory.newWorker(continuedTaskQueue);
    } else {
      w2 = testEnvironment.newWorker(continuedTaskQueue);
    }
    w2.registerWorkflowImplementationTypes(TestContinueAsNewImpl.class);
    startWorkerFor(TestContinueAsNewImpl.class);

    TestContinueAsNew client =
        workflowClient.newWorkflowStub(
            TestContinueAsNew.class, TestOptions.newWorkflowOptionsBuilder(this.taskQueue).build());
    int result = client.execute(4, continuedTaskQueue);
    assertEquals(111, result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "continueAsNew",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "continueAsNew",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "continueAsNew",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "continueAsNew",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method");
  }

  @WorkflowInterface
  public interface NoArgsWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class TestContinueAsNewNoArgsImpl implements NoArgsWorkflow {

    @Override
    public String execute() {
      NoArgsWorkflow next = Workflow.newContinueAsNewStub(NoArgsWorkflow.class);
      WorkflowInfo info = Workflow.getInfo();
      if (!info.getContinuedExecutionRunId().isPresent()) {
        next.execute();
        throw new RuntimeException("unreachable");
      } else {
        return "done";
      }
    }
  }

  @Test
  public void testContinueAsNewNoArgs() {
    startWorkerFor(TestContinueAsNewNoArgsImpl.class);

    NoArgsWorkflow client =
        workflowClient.newWorkflowStub(
            NoArgsWorkflow.class, TestOptions.newWorkflowOptionsBuilder(this.taskQueue).build());
    String result = client.execute();
    assertEquals("done", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "continueAsNew",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method");
  }

  private void assertResult(String expected, WorkflowExecution execution) {
    String result =
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(String.class);
    assertEquals(expected, result);
  }

  private void assertResult(int expected, WorkflowExecution execution) {
    int result =
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(int.class);
    assertEquals(expected, result);
  }

  private void waitForProc(WorkflowExecution execution) {
    workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(Void.class);
  }

  @Test
  public void testStart() {
    startWorkerFor(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);
    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsBuilder(taskQueue)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
    assertResult("func", WorkflowClient.start(stubF::func));
    assertEquals("func", stubF.func()); // Check that duplicated start just returns the result.
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, options);

    if (!SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      // Use worker that polls on a task queue configured through @WorkflowMethod annotation of
      // func1
      assertResult(1, WorkflowClient.start(stubF1::func1, 1));
      assertEquals(1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    }
    // Check that duplicated start is not allowed for AllowDuplicate IdReusePolicy
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build());
    assertResult("12", WorkflowClient.start(stubF2::func2, "1", 2));
    try {
      stubF2.func2("1", 2);
      fail("unreachable");
    } catch (IllegalStateException e) {
      // expected
    }
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3 stubF3 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3.class, workflowOptions);
    assertResult("123", WorkflowClient.start(stubF3::func3, "1", 2, 3));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4 stubF4 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4.class, workflowOptions);
    assertResult("1234", WorkflowClient.start(stubF4::func4, "1", 2, 3, 4));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5 stubF5 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5.class, workflowOptions);
    assertResult("12345", WorkflowClient.start(stubF5::func5, "1", 2, 3, 4, 5));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6 stubF6 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6.class, workflowOptions);
    assertResult("123456", WorkflowClient.start(stubF6::func6, "1", 2, 3, 4, 5, 6));

    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc stubP =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP::proc));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1 stubP1 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP1::proc1, "1"));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2 stubP2 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP2::proc2, "1", 2));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3 stubP3 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP3::proc3, "1", 2, 3));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4 stubP4 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP4::proc4, "1", 2, 3, 4));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5 stubP5 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP5::proc5, "1", 2, 3, 4, 5));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6 stubP6 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6.class, workflowOptions);
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
  public void testMemo() {
    if (testEnvironment != null) {
      String testMemoKey = "testKey";
      String testMemoValue = "testValue";
      Map<String, Object> memo = new HashMap<>();
      memo.put(testMemoKey, testMemoValue);

      startWorkerFor(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);
      WorkflowOptions workflowOptions =
          TestOptions.newWorkflowOptionsBuilder(taskQueue).setMemo(memo).build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
          workflowClient.newWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
      WorkflowExecution executionF = WorkflowClient.start(stubF::func);

      GetWorkflowExecutionHistoryResponse historyResp =
          WorkflowExecutionUtils.getHistoryPage(
              testEnvironment.getWorkflowService(),
              SDKTestWorkflowRule.NAMESPACE,
              executionF,
              ByteString.EMPTY,
              new NoopScope());
      HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
      Memo memoFromEvent = startEvent.getWorkflowExecutionStartedEventAttributes().getMemo();
      Payload memoBytes = memoFromEvent.getFieldsMap().get(testMemoKey);
      String memoRetrieved =
          GsonJsonPayloadConverter.getInstance().fromData(memoBytes, String.class, String.class);
      assertEquals(testMemoValue, memoRetrieved);
    }
  }

  @Test
  public void testSearchAttributes() {
    if (testEnvironment != null) {
      String testKeyString = "CustomKeywordField";
      String testValueString = "testKeyword";
      String testKeyInteger = "CustomIntField";
      Integer testValueInteger = 1;
      String testKeyDateTime = "CustomDateTimeField";
      LocalDateTime testValueDateTime = LocalDateTime.now();
      String testKeyBool = "CustomBoolField";
      Boolean testValueBool = true;
      String testKeyDouble = "CustomDoubleField";
      Double testValueDouble = 1.23;

      // add more type to test
      Map<String, Object> searchAttr = new HashMap<>();
      searchAttr.put(testKeyString, testValueString);
      searchAttr.put(testKeyInteger, testValueInteger);
      searchAttr.put(testKeyDateTime, testValueDateTime);
      searchAttr.put(testKeyBool, testValueBool);
      searchAttr.put(testKeyDouble, testValueDouble);

      startWorkerFor(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);
      WorkflowOptions workflowOptions =
          TestOptions.newWorkflowOptionsBuilder(taskQueue).setSearchAttributes(searchAttr).build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
          workflowClient.newWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
      WorkflowExecution executionF = WorkflowClient.start(stubF::func);

      GetWorkflowExecutionHistoryResponse historyResp =
          WorkflowExecutionUtils.getHistoryPage(
              testEnvironment.getWorkflowService(),
              SDKTestWorkflowRule.NAMESPACE,
              executionF,
              ByteString.EMPTY,
              new NoopScope());
      HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
      SearchAttributes searchAttrFromEvent =
          startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

      Map<String, Payload> fieldsMap = searchAttrFromEvent.getIndexedFieldsMap();
      Payload searchAttrStringBytes = fieldsMap.get(testKeyString);
      DataConverter converter = DataConverter.getDefaultInstance();
      String retrievedString =
          converter.fromPayload(searchAttrStringBytes, String.class, String.class);
      assertEquals(testValueString, retrievedString);
      Payload searchAttrIntegerBytes = fieldsMap.get(testKeyInteger);
      Integer retrievedInteger =
          converter.fromPayload(searchAttrIntegerBytes, Integer.class, Integer.class);
      assertEquals(testValueInteger, retrievedInteger);
      Payload searchAttrDateTimeBytes = fieldsMap.get(testKeyDateTime);
      LocalDateTime retrievedDateTime =
          converter.fromPayload(searchAttrDateTimeBytes, LocalDateTime.class, LocalDateTime.class);
      assertEquals(testValueDateTime, retrievedDateTime);
      Payload searchAttrBoolBytes = fieldsMap.get(testKeyBool);
      Boolean retrievedBool =
          converter.fromPayload(searchAttrBoolBytes, Boolean.class, Boolean.class);
      assertEquals(testValueBool, retrievedBool);
      Payload searchAttrDoubleBytes = fieldsMap.get(testKeyDouble);
      Double retrievedDouble =
          converter.fromPayload(searchAttrDoubleBytes, Double.class, Double.class);
      assertEquals(testValueDouble, retrievedDouble);
    }
  }

  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    startWorkerFor(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);
    WorkflowOptions workflowOptions = TestOptions.newWorkflowOptionsBuilder(taskQueue).build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
    assertEquals("func", WorkflowClient.execute(stubF::func).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    assertEquals(1, (int) WorkflowClient.execute(stubF1::func1, 1).get());
    assertEquals(1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class, workflowOptions);
    assertEquals("12", WorkflowClient.execute(stubF2::func2, "1", 2).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3 stubF3 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3.class, workflowOptions);
    assertEquals("123", WorkflowClient.execute(stubF3::func3, "1", 2, 3).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4 stubF4 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4.class, workflowOptions);
    assertEquals("1234", WorkflowClient.execute(stubF4::func4, "1", 2, 3, 4).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5 stubF5 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5.class, workflowOptions);
    assertEquals("12345", WorkflowClient.execute(stubF5::func5, "1", 2, 3, 4, 5).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6 stubF6 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6.class, workflowOptions);
    assertEquals("123456", WorkflowClient.execute(stubF6::func6, "1", 2, 3, 4, 5, 6).get());

    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc stubP =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc.class, workflowOptions);
    WorkflowClient.execute(stubP::proc).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1 stubP1 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1.class, workflowOptions);
    WorkflowClient.execute(stubP1::proc1, "1").get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2 stubP2 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2.class, workflowOptions);
    WorkflowClient.execute(stubP2::proc2, "1", 2).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3 stubP3 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3.class, workflowOptions);
    WorkflowClient.execute(stubP3::proc3, "1", 2, 3).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4 stubP4 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4.class, workflowOptions);
    WorkflowClient.execute(stubP4::proc4, "1", 2, 3, 4).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5 stubP5 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5.class, workflowOptions);
    WorkflowClient.execute(stubP5::proc5, "1", 2, 3, 4, 5).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6 stubP6 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6.class, workflowOptions);
    WorkflowClient.execute(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();

    assertEquals("proc", stubP.query());
    assertEquals("1", stubP1.query());
    assertEquals("12", stubP2.query());
    assertEquals("123", stubP3.query());
    assertEquals("1234", stubP4.query());
    assertEquals("12345", stubP5.query());
    assertEquals("123456", stubP6.query());
  }

  @Test
  public void testWorkflowIdResuePolicy() {
    startWorkerFor(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);

    // When WorkflowIdReusePolicy is not AllowDuplicate the semantics is to get result for the
    // previous run.
    String workflowId = UUID.randomUUID().toString();
    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsBuilder(taskQueue)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .setWorkflowId(workflowId)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1_1 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    assertEquals(1, stubF1_1.func1(1));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1_2 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    assertEquals(1, stubF1_2.func1(2));

    // Setting WorkflowIdReusePolicy to AllowDuplicate will trigger new run.
    workflowOptions =
        TestOptions.newWorkflowOptionsBuilder(taskQueue)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .setWorkflowId(workflowId)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1_3 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    assertEquals(2, stubF1_3.func1(2));

    // Setting WorkflowIdReusePolicy to RejectDuplicate or AllowDuplicateFailedOnly does not work as
    // expected. See https://github.com/uber/cadence-java-client/issues/295.
  }

  public static class TestChildAsyncWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
      assertEquals("func", Async.function(stubF::func).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
      assertEquals(1, (int) Async.function(stubF1::func1, 1).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class, workflowOptions);
      assertEquals("12", Async.function(stubF2::func2, "1", 2).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3 stubF3 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3.class, workflowOptions);
      assertEquals("123", Async.function(stubF3::func3, "1", 2, 3).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4 stubF4 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4.class, workflowOptions);
      assertEquals("1234", Async.function(stubF4::func4, "1", 2, 3, 4).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5 stubF5 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5.class, workflowOptions);
      assertEquals("12345", Async.function(stubF5::func5, "1", 2, 3, 4, 5).get());
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6 stubF6 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6.class, workflowOptions);
      assertEquals("123456", Async.function(stubF6::func6, "1", 2, 3, 4, 5, 6).get());

      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc stubP =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc.class, workflowOptions);
      Async.procedure(stubP::proc).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1 stubP1 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1.class, workflowOptions);
      Async.procedure(stubP1::proc1, "1").get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2 stubP2 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2.class, workflowOptions);
      Async.procedure(stubP2::proc2, "1", 2).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3 stubP3 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3.class, workflowOptions);
      Async.procedure(stubP3::proc3, "1", 2, 3).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4 stubP4 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4.class, workflowOptions);
      Async.procedure(stubP4::proc4, "1", 2, 3, 4).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5 stubP5 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5.class, workflowOptions);
      Async.procedure(stubP5::proc5, "1", 2, 3, 4, 5).get();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6 stubP6 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6.class, workflowOptions);
      Async.procedure(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();
      return null;
    }
  }

  @Test
  public void testChildAsyncWorkflow() {
    startWorkerFor(
        TestChildAsyncWorkflow.class,
        TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = WorkflowOptions.newBuilder();
    options.setWorkflowRunTimeout(Duration.ofSeconds(200));
    options.setWorkflowTaskTimeout(Duration.ofSeconds(60));
    options.setTaskQueue(taskQueue);
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskQueue));
  }

  // This workflow is designed specifically for testing some internal logic in Async.procedure
  // and ChildWorkflowStubImpl. See comments on testChildAsyncLambdaWorkflow for more details.
  @WorkflowInterface
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
      Workflow.sleep(Duration.ofSeconds(1));
      signal.complete(value);
    }
  }

  public static class TestChildAsyncLambdaWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(100))
              .setWorkflowTaskTimeout(Duration.ofSeconds(60))
              .setTaskQueue(taskQueue)
              .build();

      WaitOnSignalWorkflow child =
          Workflow.newChildWorkflowStub(WaitOnSignalWorkflow.class, workflowOptions);
      Promise<Void> promise = Async.procedure(child::execute);
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

    WorkflowOptions.Builder options = WorkflowOptions.newBuilder();
    options.setWorkflowRunTimeout(Duration.ofSeconds(200));
    options.setWorkflowTaskTimeout(Duration.ofSeconds(60));
    options.setTaskQueue(taskQueue);
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskQueue));
  }

  public static class TestUntypedChildStubWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc", workflowOptions);
      assertEquals("func", stubF.execute(String.class));
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", stubF1.execute(String.class, "1"));
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc2", workflowOptions);
      assertEquals("12", stubF2.execute(String.class, "1", 2));
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc3", workflowOptions);
      assertEquals("123", stubF3.execute(String.class, "1", 2, 3));
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc4", workflowOptions);
      assertEquals("1234", stubF4.execute(String.class, "1", 2, 3, 4));
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc5", workflowOptions);
      assertEquals("12345", stubF5.execute(String.class, "1", 2, 3, 4, 5));
      ChildWorkflowStub stubF6 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc6", workflowOptions);
      assertEquals("123456", stubF6.execute(String.class, "1", 2, 3, 4, 5, 6));

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc", workflowOptions);
      stubP.execute(Void.class);
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc1", workflowOptions);
      stubP1.execute(Void.class, "1");
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc2", workflowOptions);
      stubP2.execute(Void.class, "1", 2);
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc3", workflowOptions);
      stubP3.execute(Void.class, "1", 2, 3);
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc4", workflowOptions);
      stubP4.execute(Void.class, "1", 2, 3, 4);
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc5", workflowOptions);
      stubP5.execute(Void.class, "1", 2, 3, 4, 5);
      ChildWorkflowStub stubP6 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc6", workflowOptions);
      stubP6.execute(Void.class, "1", 2, 3, 4, 5, 6);
      return null;
    }
  }

  @Test
  public void testUntypedChildStubWorkflow() {
    startWorkerFor(
        TestUntypedChildStubWorkflow.class,
        TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = WorkflowOptions.newBuilder();
    options.setWorkflowRunTimeout(Duration.ofSeconds(200));
    options.setWorkflowTaskTimeout(Duration.ofSeconds(60));
    options.setTaskQueue(taskQueue);
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskQueue));
  }

  public static class TestUntypedChildStubWorkflowAsync implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc", workflowOptions);
      assertEquals("func", stubF.executeAsync(String.class).get());
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", stubF1.executeAsync(String.class, "1").get());
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc2", workflowOptions);
      assertEquals("12", stubF2.executeAsync(String.class, "1", 2).get());
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc3", workflowOptions);
      assertEquals("123", stubF3.executeAsync(String.class, "1", 2, 3).get());
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc4", workflowOptions);
      assertEquals("1234", stubF4.executeAsync(String.class, "1", 2, 3, 4).get());
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc5", workflowOptions);
      assertEquals("12345", stubF5.executeAsync(String.class, "1", 2, 3, 4, 5).get());
      ChildWorkflowStub stubF6 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc6", workflowOptions);
      assertEquals("123456", stubF6.executeAsync(String.class, "1", 2, 3, 4, 5, 6).get());

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc", workflowOptions);
      stubP.executeAsync(Void.class).get();
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc1", workflowOptions);
      stubP1.executeAsync(Void.class, "1").get();
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc2", workflowOptions);
      stubP2.executeAsync(Void.class, "1", 2).get();
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc3", workflowOptions);
      stubP3.executeAsync(Void.class, "1", 2, 3).get();
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc4", workflowOptions);
      stubP4.executeAsync(Void.class, "1", 2, 3, 4).get();
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc5", workflowOptions);
      stubP5.executeAsync(Void.class, "1", 2, 3, 4, 5).get();
      ChildWorkflowStub stubP6 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc6", workflowOptions);
      stubP6.executeAsync(Void.class, "1", 2, 3, 4, 5, 6).get();
      return null;
    }
  }

  @Test
  public void testUntypedChildStubWorkflowAsync() {
    startWorkerFor(
        TestUntypedChildStubWorkflowAsync.class,
        TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = WorkflowOptions.newBuilder();
    options.setWorkflowRunTimeout(Duration.ofSeconds(200));
    options.setWorkflowTaskTimeout(Duration.ofSeconds(60));
    options.setTaskQueue(taskQueue);
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskQueue));
  }

  public static class TestUntypedChildStubWorkflowAsyncInvoke
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc", workflowOptions);
      assertEquals("func", Async.function(stubF::<String>execute, String.class).get());
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", Async.function(stubF1::<String>execute, String.class, "1").get());
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc2", workflowOptions);
      assertEquals("12", Async.function(stubF2::<String>execute, String.class, "1", 2).get());
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc3", workflowOptions);
      assertEquals("123", Async.function(stubF3::<String>execute, String.class, "1", 2, 3).get());
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc4", workflowOptions);
      assertEquals(
          "1234", Async.function(stubF4::<String>execute, String.class, "1", 2, 3, 4).get());
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsFunc5", workflowOptions);
      assertEquals(
          "12345", Async.function(stubF5::<String>execute, String.class, "1", 2, 3, 4, 5).get());

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc", workflowOptions);
      Async.procedure(stubP::<Void>execute, Void.class).get();
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc1", workflowOptions);
      Async.procedure(stubP1::<Void>execute, Void.class, "1").get();
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc2", workflowOptions);
      Async.procedure(stubP2::<Void>execute, Void.class, "1", 2).get();
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc3", workflowOptions);
      Async.procedure(stubP3::<Void>execute, Void.class, "1", 2, 3).get();
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc4", workflowOptions);
      Async.procedure(stubP4::<Void>execute, Void.class, "1", 2, 3, 4).get();
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub("TestMultiargsWorkflowsProc5", workflowOptions);
      Async.procedure(stubP5::<Void>execute, Void.class, "1", 2, 3, 4, 5).get();
      return null;
    }
  }

  @Test
  public void testUntypedChildStubWorkflowAsyncInvoke() {
    startWorkerFor(
        TestUntypedChildStubWorkflowAsyncInvoke.class,
        TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class);

    WorkflowOptions.Builder options = WorkflowOptions.newBuilder();
    options.setWorkflowRunTimeout(Duration.ofSeconds(200));
    options.setWorkflowTaskTimeout(Duration.ofSeconds(60));
    options.setTaskQueue(taskQueue);
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options.build());
    assertEquals(null, client.execute(taskQueue));
  }

  public static class TestTimerWorkflowImpl implements TestWorkflows.TestWorkflow2 {

    @Override
    public String execute(boolean useExternalService) {
      Promise<Void> timer1;
      Promise<Void> timer2;
      Duration timeout1 = useExternalService ? Duration.ofMillis(700) : Duration.ofSeconds(700);
      Duration timeout2 = useExternalService ? Duration.ofMillis(1300) : Duration.ofSeconds(1300);
      timer1 = Workflow.newTimer(timeout1);
      timer2 = Workflow.newTimer(timeout2);
      long time = Workflow.currentTimeMillis();
      timer1
          .thenApply(
              r -> {
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
      assertTrue(slept + "<" + timeout1.toMillis(), slept >= timeout1.toMillis());
      timer2.get();
      slept = Workflow.currentTimeMillis() - time;
      assertTrue(String.valueOf(slept), slept >= timeout2.toMillis());
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
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      options = TestOptions.newWorkflowOptionsBuilder(taskQueue).build();
    } else {
      options =
          TestOptions.newWorkflowOptionsBuilder(taskQueue)
              .setWorkflowRunTimeout(Duration.ofDays(1))
              .build();
    }
    TestWorkflows.TestWorkflow2 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow2.class, options);
    String result = client.execute(SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);
    assertEquals("testTimer", result);
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      tracer.setExpected(
          "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
          "registerQuery getTrace",
          "newThread workflow-method",
          "newTimer PT0.7S",
          "newTimer PT1.3S",
          "currentTimeMillis",
          "newTimer PT10S",
          "currentTimeMillis",
          "currentTimeMillis",
          "currentTimeMillis");
    } else {
      tracer.setExpected(
          "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
          "registerQuery getTrace",
          "newThread workflow-method",
          "newTimer PT11M40S",
          "newTimer PT21M40S",
          "currentTimeMillis",
          "newTimer PT10H",
          "currentTimeMillis",
          "currentTimeMillis",
          "currentTimeMillis");
    }
  }

  private static final RetryOptions retryOptions =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(1))
          .setBackoffCoefficient(1)
          .build();

  public static class TestAsyncRetryWorkflowImpl implements TestWorkflows.TestWorkflow2 {

    private final List<String> trace = new ArrayList<>();

    @Override
    public String execute(boolean useExternalService) {
      trace.clear(); // clear because of replay
      trace.add("started");
      Async.retry(
              retryOptions,
              Optional.of(Duration.ofSeconds(2)),
              () -> {
                trace.add("retry at " + Workflow.currentTimeMillis());
                return Workflow.newFailedPromise(
                    ApplicationFailure.newFailure("simulated", "test"));
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
    TestWorkflows.TestWorkflow2 client =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow2.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = null;
    try {
      result = client.execute(SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals("test", ((ApplicationFailure) e.getCause()).getType());
      assertEquals(
          "message='simulated', type='test', nonRetryable=false", e.getCause().getMessage());
    }
    assertNull(result);
    List<String> trace = client.getTrace();
    assertEquals(trace.toString(), 3, trace.size());
    assertEquals("started", trace.get(0));
    assertTrue(trace.get(1).startsWith("retry at "));
    assertTrue(trace.get(2).startsWith("retry at "));
  }

  public static class TestAsyncRetryOptionsChangeWorkflow implements TestWorkflows.TestWorkflow2 {

    private final List<String> trace = new ArrayList<>();

    @Override
    public String execute(boolean useExternalService) {
      RetryOptions retryOptions;
      if (Workflow.isReplaying()) {
        retryOptions =
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .build();
      } else {
        retryOptions =
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(2)
                .build();
      }

      trace.clear(); // clear because of replay
      trace.add("started");
      Async.retry(
              retryOptions,
              Optional.of(Duration.ofSeconds(2)),
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
    startWorkerFor(
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(IllegalThreadStateException.class)
            .build(),
        TestAsyncRetryOptionsChangeWorkflow.class);
    TestWorkflows.TestWorkflow2 client =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow2.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = null;
    try {
      result = client.execute(SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals(
          IllegalThreadStateException.class.getName(),
          ((ApplicationFailure) e.getCause()).getType());
      assertEquals(
          "message='simulated', type='java.lang.IllegalThreadStateException', nonRetryable=false",
          e.getCause().getMessage());
    }
    assertNull(result);
    List<String> trace = client.getTrace();
    assertEquals(trace.toString(), 3, trace.size());
    assertEquals("started", trace.get(0));
    assertTrue(trace.get(1).startsWith("retry at "));
    assertTrue(trace.get(2).startsWith("retry at "));
  }

  @WorkflowInterface
  public interface TestExceptionPropagation {
    @WorkflowMethod
    void execute(String taskQueue);
  }

  public static class ThrowingChild implements TestWorkflows.TestWorkflow1 {

    @Override
    @SuppressWarnings("AssertionFailureIgnored")
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class,
              TestOptions.newActivityOptions20sScheduleToClose()
                  .toBuilder()
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      try {
        testActivities.throwIO();
        fail("unreachable");
        return "ignored";
      } catch (ActivityFailure e) {
        try {
          assertTrue(e.getMessage().contains("ThrowIO"));
          assertTrue(e.getCause() instanceof ApplicationFailure);
          assertEquals(IOException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
          assertEquals(
              "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
              e.getCause().getMessage());
        } catch (AssertionError ae) {
          // Errors cause workflow task to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
        Exception ee = new NumberFormatException();
        ee.initCause(new Throwable("simulated throwable", e));
        throw Workflow.wrap(ee);
      }
    }
  }

  public static class TestExceptionPropagationImpl implements TestExceptionPropagation {

    @Override
    @SuppressWarnings("AssertionFailureIgnored")
    public void execute(String taskQueue) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowRunTimeout(Duration.ofHours(1)).build();
      TestWorkflows.TestWorkflow1 child =
          Workflow.newChildWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
      try {
        child.execute(taskQueue);
        fail("unreachable");
      } catch (RuntimeException e) {
        Throwable c1 = e.getCause();
        Throwable c2 = c1.getCause();
        Throwable c3 = c2.getCause();
        Throwable c4 = c3.getCause();
        try {
          assertNoEmptyStacks(e);
          assertTrue(e.getMessage().contains("TestWorkflow1"));
          assertTrue(e instanceof ChildWorkflowFailure);
          assertTrue(c1 instanceof ApplicationFailure);
          assertEquals(NumberFormatException.class.getName(), ((ApplicationFailure) c1).getType());
          assertEquals(Throwable.class.getName(), ((ApplicationFailure) c2).getType());
          assertTrue(c3 instanceof ActivityFailure);
          assertTrue(c4 instanceof ApplicationFailure);
          assertEquals(IOException.class.getName(), ((ApplicationFailure) c4).getType());
          assertEquals(
              "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
              c4.getMessage());
        } catch (AssertionError ae) {
          // Errors cause workflow task to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
        Exception fnf = new FileNotFoundException("simulated exception");
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
   * {@link WorkflowFailedException}
   *     ->{@link ChildWorkflowFailure}
   *         ->{@link ActivityFailure}
   *             ->OriginalActivityException
   * </pre>
   *
   * <p>This test also tests that Checked exception wrapping and unwrapping works producing a nice
   * exception chain without the wrappers.
   */
  @Test
  public void testExceptionPropagation() {
    startWorkerFor(
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(NumberFormatException.class, FileNotFoundException.class)
            .build(),
        ThrowingChild.class,
        TestExceptionPropagationImpl.class);
    TestExceptionPropagation client =
        workflowClient.newWorkflowStub(
            TestExceptionPropagation.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      client.execute(taskQueue);
      fail("Unreachable");
    } catch (WorkflowFailedException e) {
      // Rethrow the assertion failure
      Throwable c1 = e.getCause();
      Throwable c2 = c1.getCause();
      Throwable c3 = c2.getCause();
      Throwable c4 = c3.getCause();
      Throwable c5 = c4.getCause();
      Throwable c6 = c5.getCause();
      if (c2 instanceof AssertionError) {
        throw (AssertionError) c2;
      }
      assertNoEmptyStacks(e);
      // Uncomment to see the actual trace.
      //            e.printStackTrace();
      assertTrue(e.getMessage(), e.getMessage().contains("TestExceptionPropagation"));
      assertTrue(e.getStackTrace().length > 0);
      assertTrue(c1 instanceof ApplicationFailure);
      assertEquals(FileNotFoundException.class.getName(), ((ApplicationFailure) c1).getType());
      assertTrue(c2 instanceof ChildWorkflowFailure);
      assertTrue(c3 instanceof ApplicationFailure);
      assertEquals(NumberFormatException.class.getName(), ((ApplicationFailure) c3).getType());
      assertEquals(Throwable.class.getName(), ((ApplicationFailure) c4).getType());
      assertTrue(c5 instanceof ActivityFailure);
      assertTrue(c6 instanceof ApplicationFailure);
      assertEquals(IOException.class.getName(), ((ApplicationFailure) c6).getType());
      assertEquals(
          "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
          c6.getMessage());
    }
  }

  @WorkflowInterface
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
    // Test getTrace through replay by a local worker.
    Worker queryWorker;
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      WorkerFactory workerFactory = WorkerFactory.newInstance(workflowClient);
      queryWorker = workerFactory.newWorker(taskQueue);
    } else {
      queryWorker = testEnvironment.newWorker(taskQueue);
    }
    queryWorker.registerWorkflowImplementationTypes(TestSignalWorkflowImpl.class);
    startWorkerFor(TestSignalWorkflowImpl.class);
    WorkflowOptions.Builder optionsBuilder = TestOptions.newWorkflowOptionsBuilder(taskQueue);
    String workflowId = UUID.randomUUID().toString();
    optionsBuilder.setWorkflowId(workflowId);
    QueryableWorkflow client =
        workflowClient.newWorkflowStub(QueryableWorkflow.class, optionsBuilder.build());
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(client::execute);

    sleep(Duration.ofSeconds(1));
    assertEquals(workflowId, execution.getWorkflowId());
    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    assertEquals("initial", client.getState());
    sleep(Duration.ofSeconds(1));

    client.mySignal("Hello ");
    sleep(Duration.ofSeconds(1));

    // Test client created using WorkflowExecution
    QueryableWorkflow client2 =
        workflowClient.newWorkflowStub(
            QueryableWorkflow.class, execution.getWorkflowId(), Optional.of(execution.getRunId()));
    assertEquals("Hello ", client2.getState());

    sleep(Duration.ofMillis(500));
    client2.mySignal("World!");
    sleep(Duration.ofMillis(500));
    assertEquals("World!", client2.getState());
    assertEquals(
        "Hello World!",
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(String.class));
    client2.execute();
  }

  public static class TestSignalWithStartWorkflowImpl implements QueryableWorkflow {

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
      state = value;
      signals.add(value);
      if (signals.size() == 2) {
        promise.complete(null);
      }
    }
  }

  @Test
  public void testSignalWithStart() throws Exception {
    // Test getTrace through replay by a local worker.
    Worker queryWorker;
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      WorkerFactory workerFactory = WorkerFactory.newInstance(workflowClient);
      queryWorker = workerFactory.newWorker(taskQueue);
    } else {
      queryWorker = testEnvironment.newWorker(taskQueue);
    }
    queryWorker.registerWorkflowImplementationTypes(TestSignalWithStartWorkflowImpl.class);
    startWorkerFor(TestSignalWorkflowImpl.class);
    WorkflowOptions.Builder optionsBuilder = TestOptions.newWorkflowOptionsBuilder(taskQueue);
    String workflowId = UUID.randomUUID().toString();
    optionsBuilder.setWorkflowId(workflowId);
    QueryableWorkflow client =
        workflowClient.newWorkflowStub(QueryableWorkflow.class, optionsBuilder.build());

    // SignalWithStart starts a workflow and delivers the signal to it.
    BatchRequest batch = workflowClient.newSignalWithStartRequest();
    batch.add(client::mySignal, "Hello ");
    batch.add(client::execute);
    WorkflowExecution execution = workflowClient.signalWithStart(batch);
    sleep(Duration.ofSeconds(1));

    // Test client created using WorkflowExecution
    QueryableWorkflow client2 =
        workflowClient.newWorkflowStub(QueryableWorkflow.class, optionsBuilder.build());
    // SignalWithStart delivers the signal to the already running workflow.
    BatchRequest batch2 = workflowClient.newSignalWithStartRequest();
    batch2.add(client2::mySignal, "World!");
    batch2.add(client2::execute);
    WorkflowExecution execution2 = workflowClient.signalWithStart(batch2);
    assertEquals(execution, execution2);

    sleep(Duration.ofMillis(500));
    assertEquals("World!", client2.getState());
    assertEquals(
        "Hello World!",
        workflowClient.newUntypedWorkflowStub(execution, Optional.empty()).getResult(String.class));

    // Check if that it starts closed workflow (AllowDuplicate is default IdReusePolicy)
    QueryableWorkflow client3 =
        workflowClient.newWorkflowStub(QueryableWorkflow.class, optionsBuilder.build());
    BatchRequest batch3 = workflowClient.newSignalWithStartRequest();
    batch3.add(client3::mySignal, "Hello ");
    batch3.add(client3::execute);
    WorkflowExecution execution3 = workflowClient.signalWithStart(batch3);
    assertEquals(execution.getWorkflowId(), execution3.getWorkflowId());
    client3.mySignal("World!");
    WorkflowStub untyped = WorkflowStub.fromTyped(client3);
    String result = untyped.getResult(String.class);
    assertEquals("Hello World!", result);

    // Make sure that cannot start if closed and RejectDuplicate policy
    QueryableWorkflow client4 =
        workflowClient.newWorkflowStub(
            QueryableWorkflow.class,
            optionsBuilder
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    BatchRequest batch4 = workflowClient.newSignalWithStartRequest();
    batch4.add(client4::mySignal, "Hello ");
    batch4.add(client4::execute);
    try {
      workflowClient.signalWithStart(batch4);
      fail("DuplicateWorkflowException expected");
    } catch (WorkflowExecutionAlreadyStarted e) {
      assertEquals(execution3.getRunId(), e.getExecution().getRunId());
    }
  }

  public static class TestNoQueryWorkflowImpl implements QueryableWorkflow {

    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "done";
    }

    @Override
    public String getState() {
      return "some state";
    }

    @Override
    public void mySignal(String value) {
      promise.complete(null);
    }
  }

  @Test
  public void testNoQueryThreadLeak() throws InterruptedException {
    startWorkerFor(TestNoQueryWorkflowImpl.class);
    int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
    WorkflowOptions.Builder optionsBuilder = TestOptions.newWorkflowOptionsBuilder(taskQueue);
    QueryableWorkflow client =
        workflowClient.newWorkflowStub(QueryableWorkflow.class, optionsBuilder.build());
    WorkflowClient.start(client::execute);
    sleep(Duration.ofSeconds(1));
    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    int queryCount = 100;
    for (int i = 0; i < queryCount; i++) {
      assertEquals("some state", client.getState());
      if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
        // Sleep a little bit to avoid server throttling error.
        Thread.sleep(50);
      }
    }
    client.mySignal("Hello ");
    WorkflowStub.fromTyped(client).getResult(String.class);
    // Ensures that no threads were leaked due to query
    int threadsCreated = ManagementFactory.getThreadMXBean().getThreadCount() - threadCount;
    assertTrue("query leaks threads: " + threadsCreated, threadsCreated < queryCount);
  }

  @Test
  public void testSignalUntyped() {
    startWorkerFor(TestSignalWorkflowImpl.class);
    String workflowType = QueryableWorkflow.class.getSimpleName();
    AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType, TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          assertEquals("initial", workflowStub.query("getState", String.class));
          workflowStub.signal("testSignal", "Hello ");
          sleep(Duration.ofMillis(500));
          while (!"Hello ".equals(workflowStub.query("getState", String.class))) {}
          assertEquals("Hello ", workflowStub.query("getState", String.class));
          workflowStub.signal("testSignal", "World!");
          while (!"World!".equals(workflowStub.query("getState", String.class))) {}
          assertEquals("World!", workflowStub.query("getState", String.class));
          assertEquals(
              "Hello World!",
              workflowClient
                  .newUntypedWorkflowStub(execution.get(), Optional.of(workflowType))
                  .getResult(String.class));
        });
    execution.set(workflowStub.start());
    assertEquals("Hello World!", workflowStub.getResult(String.class));
    assertEquals("World!", workflowStub.query("getState", String.class));
    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder()
                .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                .setQueryRejectCondition(QueryRejectCondition.QUERY_REJECT_CONDITION_NOT_OPEN)
                .build());
    WorkflowStub workflowStubNotOptionRejectCondition =
        client.newUntypedWorkflowStub(execution.get(), Optional.of(workflowType));
    try {
      workflowStubNotOptionRejectCondition.query("getState", String.class);
      fail("unreachable");
    } catch (WorkflowQueryRejectedException e) {
      assertEquals(
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
          e.getWorkflowExecutionStatus());
    }
  }

  static final AtomicInteger workflowTaskCount = new AtomicInteger();
  static CompletableFuture<Boolean> sendSignal;

  public static class TestSignalDuringLastWorkflowTaskWorkflowImpl implements TestWorkflowSignaled {

    private String signal;

    @Override
    public String execute() {
      if (workflowTaskCount.incrementAndGet() == 1) {
        sendSignal.complete(true);
        // Never sleep in a real workflow using Thread.sleep.
        // Here it is to simulate a race condition.
        try {
          Thread.sleep(1000);
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
  public void testSignalDuringLastWorkflowTask() {
    workflowTaskCount.set(0);
    sendSignal = new CompletableFuture<>();
    startWorkerFor(TestSignalDuringLastWorkflowTaskWorkflowImpl.class);
    WorkflowOptions.Builder options = TestOptions.newWorkflowOptionsBuilder(taskQueue);
    options.setWorkflowId("testSignalDuringLastWorkflowTask-" + UUID.randomUUID().toString());
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
    sleep(Duration.ofSeconds(2));
  }

  public static class TestTimerCallbackBlockedWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Promise<Void> timer1 = Workflow.newTimer(Duration.ZERO);
      Promise<Void> timer2 = Workflow.newTimer(Duration.ofSeconds(1));

      return timer1
          .thenApply(
              e -> {
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
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = client.execute(taskQueue);
    assertEquals("timer2Fired", result);
  }

  @WorkflowInterface
  public interface ITestChild {

    @WorkflowMethod
    String execute(String arg, int delay);
  }

  @WorkflowInterface
  public interface ITestNamedChild {

    @WorkflowMethod(name = "namedChild")
    String execute(String arg);
  }

  private static String child2Id;

  public static class TestParentWorkflow implements TestWorkflows.TestWorkflow1 {

    private final ITestChild child1 = Workflow.newChildWorkflowStub(ITestChild.class);
    private final ITestNamedChild child2;

    public TestParentWorkflow() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowId(child2Id).build();
      child2 = Workflow.newChildWorkflowStub(ITestNamedChild.class, options);
    }

    @Override
    public String execute(String taskQueue) {
      Promise<String> r1 = Async.function(child1::execute, "Hello ", 0);
      String r2 = child2.execute("World!");
      assertEquals(child2Id, Workflow.getWorkflowExecution(child2).get().getWorkflowId());
      return r1.get() + r2;
    }
  }

  public static class TestParentWorkflowWithChildTimeout implements TestWorkflows.TestWorkflow1 {

    private final ITestChild child;

    public TestParentWorkflowWithChildTimeout() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowRunTimeout(Duration.ofSeconds(1)).build();
      child = Workflow.newChildWorkflowStub(ITestChild.class, options);
    }

    @Override
    public String execute(String taskQueue) {
      try {
        child.execute("Hello ", (int) Duration.ofDays(1).toMillis());
      } catch (Exception e) {
        return Throwables.getStackTraceAsString(e);
      }
      throw new RuntimeException("not reachable");
    }
  }

  public static class TestChild implements ITestChild {

    @Override
    public String execute(String arg, int delay) {
      Workflow.sleep(delay);
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
    child2Id = UUID.randomUUID().toString();
    startWorkerFor(TestParentWorkflow.class, TestNamedChild.class, TestChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    assertEquals("HELLO WORLD!", client.execute(taskQueue));
  }

  @Test
  public void testChildWorkflowTimeout() {
    child2Id = UUID.randomUUID().toString();
    startWorkerFor(TestParentWorkflowWithChildTimeout.class, TestChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = client.execute(taskQueue);
    assertTrue(result, result.contains("ChildWorkflowFailure"));
    assertTrue(result, result.contains("TimeoutFailure"));
  }

  public static class TestParentWorkflowContinueAsNew implements TestWorkflows.TestWorkflow1 {

    private final ITestChild child1 =
        Workflow.newChildWorkflowStub(
            ITestChild.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    private final TestWorkflows.TestWorkflow1 self =
        Workflow.newContinueAsNewStub(TestWorkflows.TestWorkflow1.class);

    @Override
    public String execute(String arg) {
      child1.execute("Hello", 0);
      if (arg.length() > 0) {
        self.execute(""); // continue as new
      }
      return "foo";
    }
  }

  /** Reproduction of a bug when a child of continued as new workflow has the same UUID ID. */
  @Test
  public void testParentContinueAsNew() {
    child2Id = UUID.randomUUID().toString();
    startWorkerFor(TestParentWorkflowContinueAsNew.class, TestChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    assertEquals("foo", client.execute("not empty"));
  }

  private static final String childReexecuteId = UUID.randomUUID().toString();

  @WorkflowInterface
  public interface WorkflowIdReusePolicyParent {

    @WorkflowMethod
    String execute(boolean parallel, WorkflowIdReusePolicy policy);
  }

  public static class TestChildReexecuteWorkflow implements WorkflowIdReusePolicyParent {

    public TestChildReexecuteWorkflow() {}

    @Override
    public String execute(boolean parallel, WorkflowIdReusePolicy policy) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
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
      ChildWorkflowStub child2Stub = ChildWorkflowStub.fromTyped(child2);
      // Same as String r2 = child2.execute("World!");
      String r2 = child2Stub.execute(String.class, "World!");
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

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    WorkflowIdReusePolicyParent client =
        workflowClient.newWorkflowStub(WorkflowIdReusePolicyParent.class, options);
    try {
      client.execute(false, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
    }
  }

  @Test
  public void testChildStartTwice() {
    startWorkerFor(TestChildReexecuteWorkflow.class, TestNamedChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    WorkflowIdReusePolicyParent client =
        workflowClient.newWorkflowStub(WorkflowIdReusePolicyParent.class, options);
    try {
      client.execute(true, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
    }
  }

  @Test
  public void testChildReexecute() {
    startWorkerFor(TestChildReexecuteWorkflow.class, TestNamedChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(200))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    WorkflowIdReusePolicyParent client =
        workflowClient.newWorkflowStub(WorkflowIdReusePolicyParent.class, options);
    assertEquals(
        "HELLO WORLD!",
        client.execute(false, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE));
  }

  public static class TestChildWorkflowRetryWorkflow implements TestWorkflows.TestWorkflow1 {

    private ITestChild child;

    public TestChildWorkflowRetryWorkflow() {}

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(500))
              .setWorkflowTaskTimeout(Duration.ofSeconds(2))
              .setTaskQueue(taskQueue)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      child = Workflow.newChildWorkflowStub(ITestChild.class, options);

      return child.execute(taskQueue, 0);
    }
  }

  public static class AlteredTestChildWorkflowRetryWorkflow
      extends TestChildWorkflowAsyncRetryWorkflow {

    public AlteredTestChildWorkflowRetryWorkflow() {}

    @Override
    public String execute(String taskQueue) {
      Workflow.sleep(Duration.ofMinutes(1));
      return super.execute(taskQueue);
    }
  }

  @ActivityInterface
  public interface AngryChildActivity {

    @ActivityMethod
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
    public String execute(String taskQueue, int delay) {
      AngryChildActivity activity =
          Workflow.newActivityStub(
              AngryChildActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      activity.execute();
      throw ApplicationFailure.newFailure("simulated failure", "test");
    }
  }

  @Test
  public void testChildWorkflowRetry() {
    AngryChildActivityImpl angryChildActivity = new AngryChildActivityImpl();
    worker.registerActivitiesImplementations(angryChildActivity);
    startWorkerFor(
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(UnsupportedOperationException.class)
            .build(),
        TestChildWorkflowRetryWorkflow.class,
        AngryChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    try {
      client.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.toString(), e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.toString(), e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("test", ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(
          "message='simulated failure', type='test', nonRetryable=false",
          e.getCause().getCause().getMessage());
    }
    assertEquals("TestWorkflow1", lastStartedWorkflowType.get());
    assertEquals(3, angryChildActivity.getInvocationCount());
    WorkflowExecution execution = WorkflowStub.fromTyped(client).getExecution();
    SDKTestWorkflowRule.regenerateHistoryForReplay(
        service, execution, "testChildWorkflowRetryHistory");
  }

  /**
   * Tests that history that was created before server side retry was supported is backwards
   * compatible with the client that supports the server side retry.
   */
  @Test
  public void testChildWorkflowRetryReplay() throws Exception {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testChildWorkflowRetryHistory.json", TestChildWorkflowRetryWorkflow.class);
  }

  /** Tests that WorkflowReplayer fails if replay does not match workflow run. */
  @Test(expected = RuntimeException.class)
  public void testAlteredWorkflowReplayFailure() throws Exception {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testChildWorkflowRetryHistory.json", AlteredTestChildWorkflowRetryWorkflow.class);
  }

  public static class TestChildWorkflowExecutionPromiseHandler
      implements TestWorkflows.TestWorkflow1 {

    private ITestNamedChild child;

    @Override
    public String execute(String taskQueue) {
      child = Workflow.newChildWorkflowStub(ITestNamedChild.class);
      Promise<String> childResult = Async.function(child::execute, "foo");
      Promise<WorkflowExecution> executionPromise = Workflow.getWorkflowExecution(child);
      CompletablePromise<String> result = Workflow.newPromise();
      // Ensure that the callback can execute Workflow.* functions.
      executionPromise.thenApply(
          (we) -> {
            Workflow.sleep(Duration.ofSeconds(1));
            result.complete(childResult.get());
            return null;
          });
      return result.get();
    }
  }

  /** Tests that handler of the WorkflowExecution promise is executed in a workflow thread. */
  @Test
  public void testChildWorkflowExecutionPromiseHandler() {
    startWorkerFor(TestChildWorkflowExecutionPromiseHandler.class, TestNamedChild.class);

    WorkflowClient wc;
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      wc =
          WorkflowClient.newInstance(
              service,
              WorkflowClientOptions.newBuilder()
                  .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                  .build());
    } else {
      wc = testEnvironment.getWorkflowClient();
    }
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        wc.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = client.execute(taskQueue);
    assertEquals("FOO", result);
  }

  public static class TestSignalExternalWorkflow implements TestWorkflowSignaled {

    private final SignalingChild child = Workflow.newChildWorkflowStub(SignalingChild.class);

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          Async.function(child::execute, "Hello", Workflow.getInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal1(String arg) {
      fromSignal.complete(arg);
    }
  }

  @WorkflowInterface
  public interface SignalingChild {

    @WorkflowMethod
    String execute(String arg, String parentWorkflowId);
  }

  public static class SignalingChildImpl implements SignalingChild {

    @Override
    public String execute(String greeting, String parentWorkflowId) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId(parentWorkflowId).build();
      TestWorkflowSignaled parent =
          Workflow.newExternalWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      ExternalWorkflowStub untyped = ExternalWorkflowStub.fromTyped(parent);
      //  Same as parent.signal1("World");
      untyped.signal("testSignal", "World");
      return greeting;
    }
  }

  @Test
  public void testSignalExternalWorkflow() {
    startWorkerFor(TestSignalExternalWorkflow.class, SignalingChildImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(2000))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflowSignaled client =
        workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options);
    assertEquals("Hello World!", client.execute());
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    tracer.setExpected(
        "interceptExecuteWorkflow " + stub.getExecution().getWorkflowId(),
        "registerSignalHandlers testSignal",
        "newThread workflow-method",
        "executeChildWorkflow SignalingChild",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP, // child
        "newThread workflow-method",
        "signalExternalWorkflow " + SDKTestWorkflowRule.UUID_REGEXP + " testSignal",
        "handleSignal testSignal");
  }

  public static class TestUntypedSignalExternalWorkflow implements TestWorkflowSignaled {

    private final ChildWorkflowStub child = Workflow.newUntypedChildWorkflowStub("SignalingChild");

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          child.executeAsync(String.class, "Hello", Workflow.getInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal1(String arg) {
      fromSignal.complete(arg);
    }
  }

  public static class UntypedSignalingChildImpl implements SignalingChild {

    @Override
    public String execute(String greeting, String parentWorkflowId) {
      ExternalWorkflowStub parent = Workflow.newUntypedExternalWorkflowStub(parentWorkflowId);
      parent.signal("testSignal", "World");
      return greeting;
    }
  }

  @Test
  public void testUntypedSignalExternalWorkflow() {
    startWorkerFor(TestUntypedSignalExternalWorkflow.class, UntypedSignalingChildImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflowSignaled client =
        workflowClient.newWorkflowStub(TestWorkflowSignaled.class, options);
    assertEquals("Hello World!", client.execute());
  }

  public static class TestSignalExternalWorkflowFailure implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId("invalid id").build();
      TestWorkflowSignaled workflow =
          Workflow.newExternalWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      workflow.signal1("World");
      return "ignored";
    }
  }

  @Test
  public void testSignalExternalWorkflowFailure() {
    startWorkerFor(TestSignalExternalWorkflowFailure.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    try {
      client.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals(
          "SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND",
          ((ApplicationFailure) e.getCause()).getType());
      assertTrue(e.getCause().getMessage().contains("invalid id"));
    }
  }

  public static class TestSignalExternalWorkflowImmediateCancellation
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId("invalid id").build();
      TestWorkflowSignaled workflow =
          Workflow.newExternalWorkflowStub(TestWorkflowSignaled.class, parentExecution);
      CompletablePromise<Void> signal = Workflow.newPromise();
      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> signal.completeFrom(Async.procedure(workflow::signal1, "World")));
      scope.run();
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
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    try {
      client.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class TestChildWorkflowAsyncRetryWorkflow implements TestWorkflows.TestWorkflow1 {

    private ITestChild child;

    public TestChildWorkflowAsyncRetryWorkflow() {}

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(5))
              .setWorkflowTaskTimeout(Duration.ofSeconds(2))
              .setTaskQueue(taskQueue)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      child = Workflow.newChildWorkflowStub(ITestChild.class, options);
      return Async.function(child::execute, taskQueue, 0).get();
    }
  }

  @Test
  public void testChildWorkflowAsyncRetry() {
    AngryChildActivityImpl angryChildActivity = new AngryChildActivityImpl();
    worker.registerActivitiesImplementations(angryChildActivity);
    startWorkerFor(TestChildWorkflowAsyncRetryWorkflow.class, AngryChild.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    try {
      client.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("test", ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(
          "message='simulated failure', type='test', nonRetryable=false",
          e.getCause().getCause().getMessage());
    }
    assertEquals(3, angryChildActivity.getInvocationCount());
  }

  private static int testWorkflowTaskFailureBackoffReplayCount;

  public static class TestWorkflowTaskFailureBackoff implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      if (testWorkflowTaskFailureBackoffReplayCount++ < 2) {
        throw new Error("simulated workflow task failure");
      }
      return "result1";
    }
  }

  @Test
  public void testWorkflowTaskFailureBackoff() {
    testWorkflowTaskFailureBackoffReplayCount = 0;
    startWorkerFor(TestWorkflowTaskFailureBackoff.class);
    WorkflowOptions o =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(taskQueue)
            .build();

    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, o);
    long start = currentTimeMillis();
    String result = workflowStub.execute(taskQueue);
    long elapsed = currentTimeMillis() - start;
    assertTrue("spinned on fail workflow task", elapsed > 1000);
    assertEquals("result1", result);
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(WorkflowStub.fromTyped(workflowStub).getExecution())
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    int failedTaskCount = 0;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED) {
        failedTaskCount++;
      }
    }
    assertEquals(1, failedTaskCount);
  }

  public static class TestWorkflowTaskNPEBackoff implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      if (testWorkflowTaskFailureBackoffReplayCount++ < 2) {
        throw new NullPointerException("simulated workflow task failure");
      }
      return "result1";
    }
  }

  @Test
  public void testWorkflowTaskNPEBackoff() {
    testWorkflowTaskFailureBackoffReplayCount = 0;
    startWorkerFor(TestWorkflowTaskNPEBackoff.class);
    WorkflowOptions o =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(10))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(taskQueue)
            .build();

    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, o);
    long start = currentTimeMillis();
    String result = workflowStub.execute(taskQueue);
    long elapsed = currentTimeMillis() - start;
    assertTrue("spinned on fail workflow task", elapsed > 1000);
    assertEquals("result1", result);
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(WorkflowStub.fromTyped(workflowStub).getExecution())
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    int failedTaskCount = 0;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED) {
        failedTaskCount++;
      }
    }
    assertEquals(1, failedTaskCount);
  }

  public static class TestAwait implements TestWorkflows.TestWorkflow1 {

    private int i;
    private int j;

    @Override
    public String execute(String taskQueue) {
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
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals(" awoken i=1 loop i=1 awoken i=2 loop i=2 awoken i=3", result);
  }

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @WorkflowInterface
  public interface TestWorkflowRetry {

    @WorkflowMethod
    String execute(String testName);
  }

  public static class TestWorkflowRetryImpl implements TestWorkflowRetry {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int attempt = Workflow.getInfo().getAttempt();
      assertEquals(count.get() + 1, attempt);
      throw ApplicationFailure.newFailure("simulated " + count.incrementAndGet(), "test");
    }
  }

  @Test
  public void testWorkflowRetry() {
    startWorkerFor(TestWorkflowRetryImpl.class);
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(3)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflowRetry workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflowRetry.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setRetryOptions(workflowRetryOptions)
                .build());
    long start = currentTimeMillis();
    try {
      workflowStub.execute(testName.getMethodName());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertEquals(
          e.toString(),
          "message='simulated 3', type='test', nonRetryable=false",
          e.getCause().getMessage());
    } finally {
      long elapsed = currentTimeMillis() - start;
      assertTrue(String.valueOf(elapsed), elapsed >= 2000); // Ensure that retry delays the restart
    }
  }

  public static class TestWorkflowRetryDoNotRetryException implements TestWorkflowRetry {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      if (c < 3) {
        throw new IllegalArgumentException("simulated " + c);
      } else {
        throw ApplicationFailure.newFailure("simulated " + c, "NonRetryable");
      }
    }
  }

  @Test
  public void testWorkflowRetryDoNotRetryException() {
    startWorkerFor(TestWorkflowRetryDoNotRetryException.class);
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setDoNotRetry("NonRetryable")
            .setMaximumAttempts(100)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflowRetry workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflowRetry.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setRetryOptions(workflowRetryOptions)
                .build());
    try {
      workflowStub.execute(testName.getMethodName());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals("NonRetryable", ((ApplicationFailure) e.getCause()).getType());
      assertEquals(
          "message='simulated 3', type='NonRetryable', nonRetryable=false",
          e.getCause().getMessage());
    }
  }

  public static class TestWorkflowNonRetryableFlag implements TestWorkflowRetry {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      ApplicationFailure f =
          ApplicationFailure.newFailure("simulated " + c, "foo", "details1", 123);
      if (c == 3) {
        f.setNonRetryable(true);
      }
      throw f;
    }
  }

  @Test
  public void testWorkflowFailureNonRetryableFlag() {
    startWorkerFor(TestWorkflowNonRetryableFlag.class);
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(100)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflowRetry workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflowRetry.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setRetryOptions(workflowRetryOptions)
                .build());
    try {
      workflowStub.execute(testName.getMethodName());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals("foo", ((ApplicationFailure) e.getCause()).getType());
      assertEquals(
          "details1", ((ApplicationFailure) e.getCause()).getDetails().get(0, String.class));
      assertEquals(
          Integer.valueOf(123),
          ((ApplicationFailure) e.getCause()).getDetails().get(1, Integer.class));
      assertEquals(
          "message='simulated 3', type='foo', nonRetryable=true", e.getCause().getMessage());
    }
  }

  @WorkflowInterface
  public interface TestWorkflowRetryWithMethodRetry {

    @WorkflowMethod
    @MethodRetry(
        initialIntervalSeconds = 1,
        maximumIntervalSeconds = 1,
        maximumAttempts = 30,
        doNotRetry = "java.lang.IllegalArgumentException")
    String execute(String testName);
  }

  public static class TestWorkflowRetryWithMethodRetryImpl
      implements TestWorkflowRetryWithMethodRetry {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      if (c < 3) {
        throw new IllegalStateException("simulated " + c);
      } else {
        throw new IllegalArgumentException("simulated " + c);
      }
    }
  }

  @Test
  public void testWorkflowRetryWithMethodRetryDoNotRetryException() {
    startWorkerFor(
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(
                IllegalStateException.class, IllegalArgumentException.class)
            .build(),
        TestWorkflowRetryWithMethodRetryImpl.class);
    TestWorkflowRetryWithMethodRetry workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflowRetryWithMethodRetry.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(testName.getMethodName());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals(
          IllegalArgumentException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
      assertEquals(
          "message='simulated 3', type='java.lang.IllegalArgumentException', nonRetryable=false",
          e.getCause().getMessage());
    }
  }

  @WorkflowInterface
  public interface TestWorkflowWithCronSchedule {
    @WorkflowMethod
    @CronSchedule("0 * * * *")
    String execute(String testName);
  }

  static String lastCompletionResult;
  static Optional<Exception> lastFail;

  public static class TestWorkflowWithCronScheduleImpl implements TestWorkflowWithCronSchedule {

    @Override
    public String execute(String testName) {
      Logger log = Workflow.getLogger(TestWorkflowWithCronScheduleImpl.class);

      if (CancellationScope.current().isCancelRequested()) {
        log.debug("TestWorkflowWithCronScheduleImpl run canceled.");
        return null;
      }

      lastCompletionResult = Workflow.getLastCompletionResult(String.class);
      lastFail = Workflow.getPreviousRunFailure();

      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();

      if (c == 3) {
        throw ApplicationFailure.newFailure("simulated error", "test");
      }

      SimpleDateFormat sdf = new SimpleDateFormat("MMM dd,yyyy HH:mm:ss.SSS");
      Date now = new Date(Workflow.currentTimeMillis());
      log.debug("TestWorkflowWithCronScheduleImpl run at " + sdf.format(now));
      return "run " + c;
    }
  }

  @Test
  public void testWorkflowWithCronSchedule() {
    // Min interval in cron is 1min. So we will not test it against real service in Jenkins.
    // Feel free to uncomment the line below and test in local.
    Assume.assumeFalse("skipping as test will timeout", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestWorkflowWithCronScheduleImpl.class);

    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflowWithCronSchedule",
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setWorkflowRunTimeout(Duration.ofHours(1))
                .setCronSchedule("0 * * * *")
                .build());
    registerDelayedCallback(Duration.ofHours(3), client::cancel);
    client.start(testName.getMethodName());

    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    // Run 3 failed. So on run 4 we get the last completion result from run 2.
    assertEquals("run 2", lastCompletionResult);
    // The last failure ought to be the one from run 3
    assertTrue(lastFail.isPresent());
    assertTrue(lastFail.get().getMessage().contains("simulated error"));
  }

  public static class TestCronParentWorkflow implements TestWorkflows.TestWorkflow1 {

    private final TestWorkflowWithCronSchedule cronChild =
        Workflow.newChildWorkflowStub(TestWorkflowWithCronSchedule.class);

    @Override
    public String execute(String taskQueue) {
      return cronChild.execute(taskQueue);
    }
  }

  @Test
  public void testChildWorkflowWithCronSchedule() {
    // Min interval in cron is 1min. So we will not test it against real service in Jenkins.
    // Feel free to uncomment the line below and test in local.
    Assume.assumeFalse("skipping as test will timeout", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestCronParentWorkflow.class, TestWorkflowWithCronScheduleImpl.class);

    WorkflowStub client =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1",
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setWorkflowRunTimeout(Duration.ofHours(10))
                .build());
    client.start(testName.getMethodName());
    testEnvironment.sleep(Duration.ofHours(3));
    client.cancel();

    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    // Run 3 failed. So on run 4 we get the last completion result from run 2.
    assertEquals("run 2", lastCompletionResult);
  }

  public interface ProcInvocationQueryable {

    @QueryMethod(name = "getTrace")
    String query();
  }

  @WorkflowInterface
  public interface TestGetAttemptWorkflowsFunc {

    @WorkflowMethod
    int func();
  }

  public static class TestWorkflowLocals implements TestWorkflows.TestWorkflow1 {

    private final WorkflowThreadLocal<Integer> threadLocal =
        WorkflowThreadLocal.withInitial(() -> 2);

    private final WorkflowLocal<Integer> workflowLocal = WorkflowLocal.withInitial(() -> 5);

    @Override
    public String execute(String taskQueue) {
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
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("result=2, 100", result);
  }

  public static class TestSideEffectWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));

      long workflowTime = Workflow.currentTimeMillis();
      long time1 = Workflow.sideEffect(long.class, () -> workflowTime);
      long time2 = Workflow.sideEffect(long.class, () -> workflowTime);
      assertEquals(time1, time2);
      Workflow.sleep(Duration.ofSeconds(1));
      String result;
      if (workflowTime == time1) {
        result = "activity" + testActivities.activity1(1);
      } else {
        result = testActivities.activity2("activity2", 2);
      }
      return result;
    }
  }

  @Test
  public void testSideEffect() {
    startWorkerFor(TestSideEffectWorkflowImpl.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("activity1", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "currentTimeMillis",
        "sideEffect",
        "sideEffect",
        "sleep PT1S",
        "executeActivity customActivity1",
        "activity customActivity1");
  }

  private static final Map<String, Queue<Long>> mutableSideEffectValue =
      Collections.synchronizedMap(new HashMap<>());

  public static class TestMutableSideEffectWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      StringBuilder result = new StringBuilder();
      for (int j = 0; j < 1; j++) {
        for (int i = 0; i < 8; i++) {
          long value =
              Workflow.mutableSideEffect(
                  "id1",
                  Long.class,
                  (o, n) -> n > o,
                  () -> mutableSideEffectValue.get(taskQueue).poll());
          if (result.length() > 0) {
            result.append(", ");
          }
          result.append(value);
          // Sleep is here to ensure that mutableSideEffect works when replaying a history.
          if (i >= 8) {
            Workflow.sleep(Duration.ofSeconds(1));
          }
        }
      }
      return result.toString();
    }
  }

  @Test
  public void testMutableSideEffect() {
    startWorkerFor(TestMutableSideEffectWorkflowImpl.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    ArrayDeque<Long> values = new ArrayDeque<>();
    values.add(1234L);
    values.add(1234L);
    values.add(123L); // expected to be ignored as it is smaller than 1234.
    values.add(3456L);
    values.add(1234L); // expected to be ignored as it is smaller than 3456L.
    values.add(4234L);
    values.add(4234L);
    values.add(3456L); // expected to be ignored as it is smaller than 4234L.
    mutableSideEffectValue.put(taskQueue, values);
    String result = workflowStub.execute(taskQueue);
    assertEquals("1234, 1234, 1234, 3456, 3456, 4234, 4234, 4234", result);
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);
      String result = testActivities.activity2("activity2", 2);

      // Test version change in non-replay code.
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);

      boolean replaying = false;
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        result += "activity" + testActivities.activity1(1); // This is executed in non-replay mode.
      } else {
        replaying = true;
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 1);
        assertEquals(version2, Workflow.DEFAULT_VERSION);
        result += "activity" + testActivities.activity1(1);
      }

      // Test get version in replay mode.
      Workflow.sleep(1000);
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);
      assertTrue(replaying);
      return result;
    }
  }

  @Test
  public void testGetVersion() {
    startWorkerFor(TestGetVersionWorkflowImpl.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("activity22activity1activity1activity1", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "getVersion",
        "executeActivity Activity2",
        "activity Activity2",
        "getVersion",
        "executeActivity customActivity1",
        "activity customActivity1",
        "executeActivity customActivity1",
        "activity customActivity1",
        "sleep PT1S",
        "getVersion",
        "executeActivity customActivity1",
        "activity customActivity1");
  }

  public static class TestGetVersionSameIdOnReplay implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        Workflow.sleep(Duration.ofMinutes(1));
      } else {
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
        int version3 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 11);

        assertEquals(Workflow.DEFAULT_VERSION, version3);
        assertEquals(version2, version3);
      }

      return "test";
    }
  }

  @Test
  public void testGetVersionSameIdOnReplay() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestGetVersionSameIdOnReplay.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    workflowStub.execute(taskQueue);
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();

    // Validate that no marker is recorded
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      assertFalse(EventType.EVENT_TYPE_MARKER_RECORDED == event.getEventType());
    }
  }

  public static class TestGetVersionSameId implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        int version2 = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
      } else {
        int version2 = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 11);
        Workflow.sleep(Duration.ofMinutes(1));
        int version3 = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 11);

        assertEquals(11, version3);
        assertEquals(version2, version3);
      }

      return "test";
    }
  }

  @Test
  public void testGetVersionSameId() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestGetVersionSameId.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    workflowStub.execute(taskQueue);
  }

  public static class TestGetVersionWorkflowAddNewBefore implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + 1);
        }
      } else {
        // The updated code
        int changeBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
        if (changeBar != Workflow.DEFAULT_VERSION) {
          throw new IllegalStateException("Unexpected version: " + changeBar);
        }
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + changeFoo);
        }
      }
      Workflow.sleep(1000); // forces new workflow task
      return "test";
    }
  }

  @Test
  public void testGetVersionAddNewBefore() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestGetVersionWorkflowAddNewBefore.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    workflowStub.execute(taskQueue);
  }

  public static class TestGetVersionWorkflowReplaceGetVersionId
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        int changeFoo1 = Workflow.getVersion("changeFoo0", Workflow.DEFAULT_VERSION, 2);
        if (changeFoo1 != 2) {
          throw new IllegalStateException("Unexpected version: " + changeFoo1);
        }
        int changeFoo2 = Workflow.getVersion("changeFoo1", Workflow.DEFAULT_VERSION, 111);
        if (changeFoo2 != 111) {
          throw new IllegalStateException("Unexpected version: " + changeFoo2);
        }
      } else {
        // The updated code
        int changeBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
        if (changeBar != Workflow.DEFAULT_VERSION) {
          throw new IllegalStateException("Unexpected version: " + changeBar);
        }
        int changeFoo = Workflow.getVersion("changeFoo1", Workflow.DEFAULT_VERSION, 123);
        if (changeFoo != 111) {
          throw new IllegalStateException("Unexpected version: " + changeFoo);
        }
      }
      Workflow.sleep(1000); // forces new workflow task
      return "test";
    }
  }

  @Test
  public void testGetVersionWorkflowReplaceGetVersionId() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestGetVersionWorkflowReplaceGetVersionId.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    workflowStub.execute(taskQueue);
  }

  public static class TestGetVersionWorkflowReplaceCompletely
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        Workflow.getVersion("changeFoo0", Workflow.DEFAULT_VERSION, 2);
        Workflow.getVersion("changeFoo1", Workflow.DEFAULT_VERSION, 111);
        Workflow.getVersion("changeFoo2", Workflow.DEFAULT_VERSION, 101);
      } else {
        // The updated code
        int changeBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
        if (changeBar != Workflow.DEFAULT_VERSION) {
          throw new IllegalStateException("Unexpected version: " + changeBar);
        }
        int changeFoo = Workflow.getVersion("changeFoo10", Workflow.DEFAULT_VERSION, 123);
        if (changeFoo != Workflow.DEFAULT_VERSION) {
          throw new IllegalStateException("Unexpected version: " + changeFoo);
        }
      }
      Workflow.sleep(1000); // forces new workflow task
      return "test";
    }
  }

  @Test
  public void testGetVersionWorkflowReplaceCompletely() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestGetVersionWorkflowReplaceCompletely.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    workflowStub.execute(taskQueue);
  }

  public static class TestGetVersionWorkflowRemove implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities activities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      String result;
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + 1);
        }
        result = activities.activity2("foo", 10);
      } else {
        // No getVersionCall
        result = activities.activity2("foo", 10);
      }
      Workflow.sleep(1000); // forces new workflow task
      return result;
    }
  }

  @Test
  public void testGetVersionWorkflowRemove() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.USE_EXTERNAL_SERVICE);

    startWorkerFor(TestGetVersionWorkflowRemove.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    assertEquals("foo10", workflowStub.execute(taskQueue));
  }

  static CompletableFuture<Boolean> executionStarted = new CompletableFuture<>();

  public static class TestGetVersionWithoutCommandEventWorkflowImpl
      implements TestWorkflowSignaled {

    CompletablePromise<Boolean> signalReceived = Workflow.newPromise();

    @Override
    public String execute() {
      try {
        if (!Workflow.isReplaying()) {
          executionStarted.complete(true);
          signalReceived.get();
        } else {
          // Execute getVersion in replay mode. In this case we have no command event, only a
          // signal.
          int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
          if (version == Workflow.DEFAULT_VERSION) {
            signalReceived.get();
            return "result 1";
          } else {
            return "result 2";
          }
        }
        Workflow.sleep(1000);
      } catch (Exception e) {
        throw new RuntimeException("failed to get from signal");
      }

      throw new RuntimeException("unreachable");
    }

    @Override
    public void signal1(String arg) {
      signalReceived.complete(true);
    }
  }

  @Test
  public void testGetVersionWithoutCommandEvent() throws Exception {
    executionStarted = new CompletableFuture<>();
    startWorkerFor(TestGetVersionWithoutCommandEventWorkflowImpl.class);
    TestWorkflowSignaled workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflowSignaled.class, TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowClient.start(workflowStub::execute);
    executionStarted.get();
    workflowStub.signal1("test signal");
    String result = WorkflowStub.fromTyped(workflowStub).getResult(String.class);
    assertEquals("result 1", result);
  }

  // The following test covers the scenario where getVersion call is removed before a
  // non-version-marker command.
  public static class TestGetVersionRemovedInReplay implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      String result;
      // Test removing a version check in replay code.
      if (!Workflow.isReplaying()) {
        int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 13);
        assertEquals(13, version);
        result = testActivities.activity2("activity2", 2);
      } else {
        result = testActivities.activity2("activity2", 2);
      }
      result += testActivities.activity();
      return result;
    }
  }

  @Test
  public void testGetVersionRemovedInReplay() {
    startWorkerFor(TestGetVersionRemovedInReplay.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("activity22activity", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "getVersion",
        "executeActivity Activity2",
        "activity Activity2",
        "executeActivity Activity",
        "activity Activity");
  }

  // The following test covers the scenario where getVersion call is removed before another
  // version-marker command.
  public static class TestGetVersionRemovedBefore implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      // Test removing a version check in replay code.
      if (!Workflow.isReplaying()) {
        Workflow.getVersion("test_change1", Workflow.DEFAULT_VERSION, 11);
        Workflow.getVersion("test_change2", Workflow.DEFAULT_VERSION, 12);
        Workflow.getVersion("test_change3", Workflow.DEFAULT_VERSION, 13);
        Workflow.getVersion("test_change4", Workflow.DEFAULT_VERSION, 14);
      } else {
        int version = Workflow.getVersion("test_change3", Workflow.DEFAULT_VERSION, 22);
        assertEquals(13, version);
      }
      return testActivities.activity();
    }
  }

  @Test
  public void testGetVersionRemovedBefore() {
    startWorkerFor(TestGetVersionRemovedBefore.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("activity", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "getVersion",
        "getVersion",
        "getVersion",
        "getVersion",
        "executeActivity Activity",
        "activity Activity");
  }

  public static class TestVersionNotSupportedWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      String result = "";
      if (version == Workflow.DEFAULT_VERSION) {
        result += "activity" + testActivities.activity1(1);
      } else {
        result += testActivities.activity2("activity2", 2); // This is executed.
      }

      // Catching error from getVersion is only for unit test purpose.
      // Do not ever do it in production code.
      try {
        Workflow.getVersion("test_change", 2, 3);
      } catch (Error e) {
        throw Workflow.wrap(ApplicationFailure.newFailure("unsupported change version", "test"));
      }
      return result;
    }
  }

  @Test
  public void testVersionNotSupported() {
    startWorkerFor(TestVersionNotSupportedWorkflowImpl.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());

    try {
      workflowStub.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowException e) {
      assertEquals(
          "message='unsupported change version', type='test', nonRetryable=false",
          e.getCause().getMessage());
    }
  }

  @WorkflowInterface
  public interface DeterminismFailingWorkflow {
    @WorkflowMethod
    void execute(String taskQueue);
  }

  public static class DeterminismFailingWorkflowImpl implements DeterminismFailingWorkflow {

    @Override
    public void execute(String taskQueue) {
      TestActivities activities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      if (!Workflow.isReplaying()) {
        activities.activity1(1);
      }
    }
  }

  @Test
  public void testNonDeterministicWorkflowPolicyBlockWorkflow() {
    startWorkerFor(DeterminismFailingWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(taskQueue)
            .build();
    DeterminismFailingWorkflow workflowStub =
        workflowClient.newWorkflowStub(DeterminismFailingWorkflow.class, options);
    try {
      workflowStub.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      // expected to timeout as workflow is going get blocked.
      assertTrue(e.getCause() instanceof TimeoutFailure);
    }

    int workflowRootThreads = 0;
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
    for (ThreadInfo thread : threads) {
      if (thread.getThreadName().contains("workflow-root")) {
        workflowRootThreads++;
      }
    }

    assertTrue("workflow threads might leak", workflowRootThreads < 10);
  }

  @Test
  public void testNonDeterministicWorkflowPolicyFailWorkflow() {
    WorkflowImplementationOptions implementationOptions =
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Throwable.class)
            .build();
    worker.registerWorkflowImplementationTypes(
        implementationOptions, DeterminismFailingWorkflowImpl.class);
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workerFactory.start();
    } else {
      testEnvironment.start();
    }
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(taskQueue)
            .build();
    DeterminismFailingWorkflow workflowStub =
        workflowClient.newWorkflowStub(DeterminismFailingWorkflow.class, options);
    try {
      workflowStub.execute(taskQueue);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      // expected to fail on non deterministic error
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertEquals(
          InternalWorkflowTaskException.class.getName(),
          ((ApplicationFailure) e.getCause()).getType());
    }
  }

  public static class TestUUIDAndRandom implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      TestActivities activities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
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
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    assertEquals("foo10", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "sideEffect",
        "sideEffect",
        "executeActivity Activity2",
        "activity Activity2");
  }

  @ActivityInterface
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

  @WorkflowInterface
  public interface GenericParametersWorkflow {

    @WorkflowMethod
    List<UUID> execute(String taskQueue, List<UUID> arg1, Set<UUID> arg2);

    @SignalMethod
    void signal(List<UUID> arg);

    @QueryMethod
    List<UUID> query(List<UUID> arg);
  }

  public static class GenericParametersWorkflowImpl implements GenericParametersWorkflow {

    private List<UUID> signaled;
    private GenericParametersActivity activity;

    @Override
    public List<UUID> execute(String taskQueue, List<UUID> arg1, Set<UUID> arg2) {
      Workflow.await(() -> signaled != null && signaled.size() == 0);
      activity =
          Workflow.newActivityStub(
              GenericParametersActivity.class,
              TestOptions.newActivityOptionsForTaskQueue(taskQueue));
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
            GenericParametersWorkflow.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    List<UUID> uuidList = new ArrayList<>();
    uuidList.add(UUID.randomUUID());
    uuidList.add(UUID.randomUUID());
    Set<UUID> uuidSet = new HashSet<>();
    uuidSet.add(UUID.randomUUID());
    uuidSet.add(UUID.randomUUID());
    uuidSet.add(UUID.randomUUID());
    CompletableFuture<List<UUID>> resultF =
        WorkflowClient.execute(workflowStub::execute, taskQueue, uuidList, uuidSet);
    // Test signal and query serialization
    workflowStub.signal(uuidList);
    sleep(Duration.ofSeconds(1));
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

  public static class NonSerializableException extends RuntimeException {
    @SuppressWarnings("unused")
    private final InputStream file; // gson chokes on this field

    public NonSerializableException() {
      try {
        file = new FileInputStream(File.createTempFile("foo", "bar"));
      } catch (IOException e) {
        throw Activity.wrap(e);
      }
    }
  }

  @ActivityInterface
  public interface NonSerializableExceptionActivity {
    void execute();
  }

  public static class NonSerializableExceptionActivityImpl
      implements NonSerializableExceptionActivity {

    @Override
    public void execute() {
      throw new NonSerializableException();
    }
  }

  @ActivityInterface
  public interface NonDeserializableArgumentsActivity {
    void execute(int arg);
  }

  public static class NonDeserializableExceptionActivityImpl
      implements NonDeserializableArgumentsActivity {

    @Override
    public void execute(int arg) {}
  }

  @WorkflowInterface
  public interface NonSerializableExceptionChildWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  public static class NonSerializableExceptionChildWorkflowImpl
      implements NonSerializableExceptionChildWorkflow {

    @Override
    public String execute(String taskQueue) {
      throw new NonSerializableException();
    }
  }

  public static class TestNonSerializableExceptionInChildWorkflow
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      NonSerializableExceptionChildWorkflow child =
          Workflow.newChildWorkflowStub(NonSerializableExceptionChildWorkflow.class);
      try {
        child.execute(taskQueue);
      } catch (ChildWorkflowFailure e) {
        return e.getMessage();
      }
      return "done";
    }
  }

  @Test
  public void testNonSerializableExceptionInChildWorkflow() {
    startWorkerFor(
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(NonSerializableException.class)
            .build(),
        TestNonSerializableExceptionInChildWorkflow.class,
        NonSerializableExceptionChildWorkflowImpl.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());

    String result = workflowStub.execute(taskQueue);
    assertTrue(result.contains("NonSerializableException"));
  }

  @WorkflowInterface
  public interface TestLargeWorkflow {
    @WorkflowMethod
    String execute(int activityCount, String taskQueue);
  }

  @ActivityInterface
  public interface TestLargeWorkflowActivity {
    String activity();
  }

  public static class TestLargeWorkflowActivityImpl implements TestLargeWorkflowActivity {
    @Override
    public String activity() {
      return "done";
    }
  }

  public static class TestLargeHistory implements TestLargeWorkflow {

    @Override
    public String execute(int activityCount, String taskQueue) {
      TestLargeWorkflowActivity activities =
          Workflow.newActivityStub(
              TestLargeWorkflowActivity.class,
              TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      List<Promise<String>> results = new ArrayList<>();
      for (int i = 0; i < activityCount; i++) {
        Promise<String> result = Async.function(activities::activity);
        results.add(result);
      }
      Promise.allOf(results).get();
      return "done";
    }
  }

  @Test
  @Ignore // Requires DEBUG_TIMEOUTS=true
  public void testLargeHistory() {
    final int activityCount = 1000;
    worker.registerActivitiesImplementations(new TestLargeWorkflowActivityImpl());
    startWorkerFor(TestLargeHistory.class);
    TestLargeWorkflow workflowStub =
        workflowClient.newWorkflowStub(
            TestLargeWorkflow.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue)
                .setWorkflowTaskTimeout(Duration.ofSeconds(30))
                .build());
    long start = System.currentTimeMillis();
    String result = workflowStub.execute(activityCount, taskQueue);
    long duration = System.currentTimeMillis() - start;
    log.info(testName.toString() + " duration is " + duration);
    assertEquals("done", result);
  }

  @WorkflowInterface
  public interface WorkflowTaskTimeoutWorkflow {
    @WorkflowMethod
    String execute(String testName) throws InterruptedException;
  }

  public static class WorkflowTaskTimeoutWorkflowImpl implements WorkflowTaskTimeoutWorkflow {

    @Override
    public String execute(String testName) throws InterruptedException {

      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
        Thread.sleep(2000);
      }

      return "some result";
    }
  }

  @Test
  public void testWorkflowTaskTimeoutWorkflow() throws InterruptedException {
    startWorkerFor(WorkflowTaskTimeoutWorkflowImpl.class);

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .build();

    WorkflowTaskTimeoutWorkflow stub =
        workflowClient.newWorkflowStub(WorkflowTaskTimeoutWorkflow.class, options);
    String result = stub.execute(testName.getMethodName());
    assertEquals("some result", result);
  }

  public static class TestParallelLocalActivitiesWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    static final int COUNT = 100;

    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      List<Promise<String>> laResults = new ArrayList<>();
      Random r = Workflow.newRandom();
      for (int i = 0; i < COUNT; i++) {
        laResults.add(Async.function(localActivities::sleepActivity, (long) r.nextInt(3000), i));
      }
      Promise.allOf(laResults).get();
      return "done";
    }
  }

  public static class TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      String result = "";
      for (int i = 0; i < 5; i++) {
        result += localActivities.sleepActivity(2000, i);
      }
      return result;
    }
  }

  @Test
  public void testLocalActivitiesWorkflowTaskHeartbeat()
      throws ExecutionException, InterruptedException {
    startWorkerFor(TestLocalActivitiesWorkflowTaskHeartbeatWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(4))
            .setTaskQueue(taskQueue)
            .build();
    int count = 5;
    Future<String>[] result = new Future[count];
    for (int i = 0; i < count; i++) {
      TestWorkflows.TestWorkflow1 workflowStub =
          workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
      result[i] = WorkflowClient.execute(workflowStub::execute, taskQueue);
    }
    for (int i = 0; i < count; i++) {
      assertEquals(
          "sleepActivity0sleepActivity1sleepActivity2sleepActivity3sleepActivity4",
          result[i].get());
    }
    assertEquals(activitiesImpl.toString(), 5 * count, activitiesImpl.invocations.size());
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      return localActivities.sleepActivity(5000, 123);
    }
  }

  @Test
  public void testLongLocalActivityWorkflowTaskHeartbeat() {
    startWorkerFor(TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflowStub.execute(taskQueue);
    assertEquals("sleepActivity123", result);
    assertEquals(activitiesImpl.toString(), 1, activitiesImpl.invocations.size());
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatFailureWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {

    static boolean invoked;

    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      String result = localActivities.sleepActivity(5000, 123);
      if (!invoked) {
        invoked = true;
        throw new Error("Simulate decision failure to force replay");
      }
      return result;
    }
  }

  /**
   * Test that local activity is not lost during replay if it was started before forced
   * WorkflowTask.
   */
  @Test
  public void testLongLocalActivityWorkflowTaskHeartbeatFailure() {
    startWorkerFor(TestLongLocalActivityWorkflowTaskHeartbeatFailureWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflowStub.execute(taskQueue);
    assertEquals("sleepActivity123", result);
    assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  public static class TestParallelLocalActivityExecutionWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      TestActivities localActivities =
          Workflow.newLocalActivityStub(
              TestActivities.class, TestOptions.newLocalActivityOptions());
      List<Promise<String>> results = new ArrayList<>(4);
      for (int i = 1; i <= 4; i++) {
        results.add(Async.function(localActivities::sleepActivity, (long) 1000 * i, i));
      }

      Promise<String> result2 =
          Async.function(
              () -> {
                String result = "";
                for (int i = 0; i < 3; i++) {
                  result += localActivities.sleepActivity(1000, 21);
                }
                return result;
              });

      return results.get(0).get()
          + results.get(1).get()
          + results.get(2).get()
          + results.get(3).get()
          + result2.get();
    }
  }

  @Test
  public void testParallelLocalActivityExecutionWorkflow() {
    startWorkerFor(TestParallelLocalActivityExecutionWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(taskQueue)
            .build();
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflowStub.execute(taskQueue);
    assertEquals(
        "sleepActivity1sleepActivity2sleepActivity3sleepActivity4sleepActivity21sleepActivity21sleepActivity21",
        result);
  }

  @WorkflowInterface
  public interface TestWorkflowQuery {

    @WorkflowMethod()
    String execute(String taskQueue);

    @QueryMethod()
    String query();
  }

  @WorkflowInterface
  public interface SignalOrderingWorkflow {
    @WorkflowMethod
    List<String> run();

    @SignalMethod(name = "testSignal")
    void signal(String s);
  }

  public static class SignalOrderingWorkflowImpl implements SignalOrderingWorkflow {
    private final List<String> signals = new ArrayList<>();

    @Override
    public List<String> run() {
      Workflow.await(() -> signals.size() == 3);
      return signals;
    }

    @Override
    public void signal(String s) {
      signals.add(s);
    }
  }

  @Test
  public void testSignalOrderingWorkflow() {
    startWorkerFor(SignalOrderingWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(10))
            .setTaskQueue(taskQueue)
            .build();
    SignalOrderingWorkflow workflowStub =
        workflowClient.newWorkflowStub(SignalOrderingWorkflow.class, options);
    WorkflowClient.start(workflowStub::run);

    // Suspend polling so that all the signals will be received in the same workflow task.
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workerFactory.suspendPolling();
    } else {
      testEnvironment.getWorkerFactory().suspendPolling();
    }

    workflowStub.signal("test1");
    workflowStub.signal("test2");
    workflowStub.signal("test3");

    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workerFactory.resumePolling();
    } else {
      testEnvironment.getWorkerFactory().resumePolling();
    }

    @SuppressWarnings("unchecked")
    List<String> result =
        WorkflowStub.fromTyped(workflowStub)
            .getResult(List.class, new TypeToken<List<String>>() {}.getType());
    List<String> expected = Arrays.asList("test1", "test2", "test3");
    assertEquals(expected, result);
  }

  public interface GreetingWorkflow {

    @WorkflowMethod
    void createGreeting(String name);
  }

  public interface GreetingActivities {
    @ActivityMethod
    String composeGreeting(String string);
  }

  static class GreetingActivitiesImpl implements GreetingActivities {
    @Override
    public String composeGreeting(String string) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        throw new Error("Unexpected", e);
      }
      return "greetings: " + string;
    }
  }

  @WorkflowInterface
  public interface TestCompensationWorkflow {
    @WorkflowMethod
    void compensate();
  }

  public static class TestMultiargsWorkflowsFuncImpl
      implements TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc {

    @Override
    public String func() {
      return "done";
    }
  }

  public static class TestCompensationWorkflowImpl implements TestCompensationWorkflow {
    @Override
    public void compensate() {}
  }

  @WorkflowInterface
  public interface TestSagaWorkflow {
    @WorkflowMethod
    String execute(String taskQueue, boolean parallelCompensation);
  }

  public static class TestSagaWorkflowImpl implements TestSagaWorkflow {

    @Override
    public String execute(String taskQueue, boolean parallelCompensation) {
      TestActivities testActivities =
          Workflow.newActivityStub(
              TestActivities.class,
              TestOptions.newActivityOptionsForTaskQueue(taskQueue)
                  .toBuilder()
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());

      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF1 =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);

      Saga saga =
          new Saga(
              new Saga.Options.Builder().setParallelCompensation(parallelCompensation).build());
      try {
        testActivities.activity1(10);
        saga.addCompensation(testActivities::activity2, "compensate", -10);

        stubF1.func();

        TestCompensationWorkflow compensationWorkflow =
            Workflow.newChildWorkflowStub(TestCompensationWorkflow.class, workflowOptions);
        saga.addCompensation(compensationWorkflow::compensate);

        testActivities.throwIO();
        saga.addCompensation(
            () -> {
              throw new RuntimeException("unreachable");
            });
      } catch (Exception e) {
        saga.compensate();
      }
      return "done";
    }
  }

  @Test
  public void testSaga() {
    startWorkerFor(
        TestSagaWorkflowImpl.class,
        TestMultiargsWorkflowsFuncImpl.class,
        TestCompensationWorkflowImpl.class);
    TestSagaWorkflow sagaWorkflow =
        workflowClient.newWorkflowStub(
            TestSagaWorkflow.class, TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    sagaWorkflow.execute(taskQueue, false);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "executeActivity customActivity1",
        "activity customActivity1",
        "executeChildWorkflow TestMultiargsWorkflowsFunc",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "executeActivity ThrowIO",
        "activity ThrowIO",
        "executeChildWorkflow TestCompensationWorkflow",
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "executeActivity Activity2",
        "activity Activity2");
  }

  @Test
  public void testSagaParallelCompensation() {
    startWorkerFor(
        TestSagaWorkflowImpl.class,
        TestMultiargsWorkflowsFuncImpl.class,
        TestCompensationWorkflowImpl.class);
    TestSagaWorkflow sagaWorkflow =
        workflowClient.newWorkflowStub(
            TestSagaWorkflow.class, TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    sagaWorkflow.execute(taskQueue, true);
    String trace = tracer.getTrace();
    assertTrue(trace, trace.contains("executeChildWorkflow TestCompensationWorkflow"));
    assertTrue(trace, trace.contains("executeActivity Activity2"));
  }

  public static class TestSignalExceptionWorkflowImpl implements TestWorkflowSignaled {
    private final boolean signaled = false;

    @Override
    public String execute() {
      Workflow.await(() -> signaled);
      return null;
    }

    @Override
    public void signal1(String arg) {
      for (int i = 0; i < 10; i++) {
        Async.procedure(() -> Workflow.sleep(Duration.ofHours(1)));
      }

      throw new RuntimeException("exception in signal method");
    }
  }

  @Test
  public void testExceptionInSignal() throws InterruptedException {
    startWorkerFor(TestSignalExceptionWorkflowImpl.class);
    TestWorkflowSignaled signalWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflowSignaled.class, TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    CompletableFuture<String> result = WorkflowClient.execute(signalWorkflow::execute);
    signalWorkflow.signal1("test");
    try {
      result.get(1, TimeUnit.SECONDS);
      fail("not reachable");
    } catch (Exception e) {
      // exception expected here.
    }

    // Suspend polling so that workflow tasks are not retried. Otherwise it will affect our thread
    // count.
    if (SDKTestWorkflowRule.USE_EXTERNAL_SERVICE) {
      workerFactory.suspendPolling();
    } else {
      testEnvironment.getWorkerFactory().suspendPolling();
    }

    // Wait for workflow task retry to finish.
    Thread.sleep(10000);

    int workflowThreads = 0;
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
    for (ThreadInfo thread : threads) {
      if (thread.getThreadName().startsWith("workflow")) {
        workflowThreads++;
      }
    }

    assertTrue(
        "workflow threads might leak, #workflowThreads = " + workflowThreads, workflowThreads < 20);
  }

  @WorkflowInterface
  public interface TestUpsertSearchAttributes {
    @WorkflowMethod
    String execute(String taskQueue, String keyword);
  }

  public static class TestUpsertSearchAttributesImpl implements TestUpsertSearchAttributes {

    @Override
    public String execute(String taskQueue, String keyword) {
      SearchAttributes searchAttributes = Workflow.getInfo().getSearchAttributes();
      assertNull(searchAttributes);

      Map<String, Object> searchAttrMap = new HashMap<>();
      searchAttrMap.put("CustomKeywordField", keyword);
      Workflow.upsertSearchAttributes(searchAttrMap);

      searchAttributes = Workflow.getInfo().getSearchAttributes();
      assertEquals(
          "testKey",
          SearchAttributesUtil.getValueFromSearchAttributes(
              searchAttributes, "CustomKeywordField", String.class));

      // Running the activity below ensures that we have one more workflow task to be executed after
      // adding the search attributes. This helps with replaying the history one more time to check
      // against a possible NonDeterminisicWorkflowError which could be caused by missing
      // UpsertWorkflowSearchAttributes event in history.
      TestActivities activities =
          Workflow.newActivityStub(
              TestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      activities.activity();

      return "done";
    }
  }

  @Test
  public void testUpsertSearchAttributes() {
    startWorkerFor(TestUpsertSearchAttributesImpl.class);
    TestUpsertSearchAttributes testWorkflow =
        workflowClient.newWorkflowStub(
            TestUpsertSearchAttributes.class,
            TestOptions.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution = WorkflowClient.start(testWorkflow::execute, taskQueue, "testKey");
    String result = testWorkflow.execute(taskQueue, "testKey");
    assertEquals("done", result);
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "newThread workflow-method",
        "upsertSearchAttributes",
        "executeActivity Activity",
        "activity Activity");
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    boolean found = false;
    for (HistoryEvent event : response.getHistory().getEventsList()) {
      if (EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES == event.getEventType()) {
        found = true;
        break;
      }
    }
    assertTrue("EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES found in the history", found);
  }

  public static class TestMultiargsWorkflowsFuncChild
      implements TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 {
    @Override
    public String func2(String s, int i) {
      WorkflowInfo wi = Workflow.getInfo();
      Optional<String> parentId = wi.getParentWorkflowId();
      return parentId.get();
    }
  }

  public static class TestAttemptReturningWorkflowFunc implements TestGetAttemptWorkflowsFunc {
    @Override
    public int func() {
      WorkflowInfo wi = Workflow.getInfo();
      return wi.getAttempt();
    }
  }

  public static class TestMultiargsWorkflowsFuncParent
      implements TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc {
    @Override
    public String func() {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(100))
              .setWorkflowTaskTimeout(Duration.ofSeconds(60))
              .build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 child =
          Workflow.newChildWorkflowStub(
              TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class, workflowOptions);

      Optional<String> parentWorkflowId = Workflow.getInfo().getParentWorkflowId();
      String childsParentWorkflowId = child.func2(null, 0);

      String result =
          String.format("%s - %s", parentWorkflowId.isPresent(), childsParentWorkflowId);
      return result;
    }
  }

  @Test
  public void testParentWorkflowInfoInChildWorkflows() {
    startWorkerFor(TestMultiargsWorkflowsFuncParent.class, TestMultiargsWorkflowsFuncChild.class);

    String workflowId = "testParentWorkflowInfoInChildWorkflows";
    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsBuilder(taskQueue).setWorkflowId(workflowId).build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc parent =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);

    String result = parent.func();
    String expected = String.format("%s - %s", false, workflowId);
    assertEquals(expected, result);
  }

  @Test
  public void testGetAttemptFromWorkflowInfo() {
    startWorkerFor(TestMultiargsWorkflowsFuncParent.class, TestAttemptReturningWorkflowFunc.class);
    String workflowId = "testGetAttemptWorkflow";
    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsBuilder(taskQueue).setWorkflowId(workflowId).build();
    TestGetAttemptWorkflowsFunc workflow =
        workflowClient.newWorkflowStub(TestGetAttemptWorkflowsFunc.class, workflowOptions);
    int attempt = workflow.func();
    assertEquals(1, attempt);
  }

  public interface WorkflowBase {
    @WorkflowMethod
    String execute(String arg);
  }

  @WorkflowInterface
  public interface WorkflowA extends WorkflowBase {}

  @WorkflowInterface
  public interface WorkflowB extends WorkflowBase {}

  public static class WorkflowAImpl implements WorkflowA {
    @Override
    public String execute(String arg) {
      return "WorkflowAImpl" + arg;
    }
  }

  public static class WorkflowBImpl implements WorkflowB {
    @Override
    public String execute(String arg) {
      return "WorkflowBImpl" + arg;
    }
  }

  @Test
  public void testPolymorphicStart() {
    startWorkerFor(WorkflowBImpl.class, WorkflowAImpl.class);
    WorkflowOptions options = TestOptions.newWorkflowOptionsBuilder(taskQueue).build();
    WorkflowBase[] stubs =
        new WorkflowBase[] {
          workflowClient.newWorkflowStub(WorkflowA.class, options),
          workflowClient.newWorkflowStub(WorkflowB.class, options),
        };
    String results = stubs[0].execute("0") + ", " + stubs[1].execute("1");
    assertEquals("WorkflowAImpl0, WorkflowBImpl1", results);
  }

  public interface SignalQueryBase {
    @SignalMethod
    void signal(String arg);

    @QueryMethod
    String getSignal();
  }

  @WorkflowInterface
  public interface SignalQueryWorkflowA extends SignalQueryBase {
    @WorkflowMethod
    String execute();
  }

  public static class SignalQueryWorkflowAImpl implements SignalQueryWorkflowA {

    private String signal;

    @Override
    public void signal(String arg) {
      signal = arg;
    }

    @Override
    public String getSignal() {
      return signal;
    }

    @Override
    public String execute() {
      Workflow.await(() -> signal != null);
      return signal;
    }
  }

  @Test
  public void testSignalAndQueryInterface() {
    startWorkerFor(SignalQueryWorkflowAImpl.class);
    WorkflowOptions options = TestOptions.newWorkflowOptionsBuilder(taskQueue).build();
    SignalQueryWorkflowA stub = workflowClient.newWorkflowStub(SignalQueryWorkflowA.class, options);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    SignalQueryBase signalStub =
        workflowClient.newWorkflowStub(SignalQueryBase.class, execution.getWorkflowId());
    signalStub.signal("Hello World!");
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    String queryResult = signalStub.getSignal();
    assertEquals("Hello World!", result);
    assertEquals(queryResult, result);
  }

  @WorkflowInterface
  public interface TestSignalAndQueryListenerWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void register();
  }

  public static class TestSignalAndQueryListenerWorkflowImpl
      implements TestSignalAndQueryListenerWorkflow {

    private boolean register;
    private final List<String> signals = new ArrayList<>();

    @Override
    public void execute() {
      Workflow.await(() -> register);
      Workflow.registerListener(
          new SignalQueryBase() {

            @Override
            public void signal(String arg) {
              signals.add(arg);
            }

            @Override
            public String getSignal() {
              return String.join(", ", signals);
            }
          });
    }

    @Override
    public void register() {
      register = true;
    }
  }

  @Test
  public void testSignalAndQueryListener() {
    startWorkerFor(TestSignalAndQueryListenerWorkflowImpl.class);
    WorkflowOptions options = TestOptions.newWorkflowOptionsBuilder(taskQueue).build();
    TestSignalAndQueryListenerWorkflow stub =
        workflowClient.newWorkflowStub(TestSignalAndQueryListenerWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    SignalQueryBase signalStub =
        workflowClient.newWorkflowStub(SignalQueryBase.class, execution.getWorkflowId());
    // Send signals before listener is registered to test signal buffering
    signalStub.signal("a");
    signalStub.signal("b");
    try {
      signalStub.getSignal();
      fail("unreachable"); // as not listener is not registered yet
    } catch (WorkflowQueryException e) {
      assertTrue(e.getCause().getMessage().contains("Unknown query type: getSignal"));
    }
    stub.register();
    while (true) {
      try {
        assertEquals("a, b", signalStub.getSignal());
        break;
      } catch (WorkflowQueryException e) {
        assertTrue(e.getMessage().contains("Unknown query type: getSignal"));
      }
    }
    tracer.setExpected(
        "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
        "registerSignalHandlers register",
        "newThread workflow-method",
        "await await",
        "handleSignal register",
        "registerQuery getSignal",
        "registerSignalHandlers signal",
        "handleSignal signal",
        "handleSignal signal",
        "handleQuery getSignal",
        "query getSignal");
  }
}
