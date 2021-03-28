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
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.failure.*;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.replay.InternalWorkflowTaskException;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.*;
import io.temporal.worker.*;
import io.temporal.workflow.shared.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
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
  private static final List<ScheduledFuture<?>> delayedCallbacks = new ArrayList<>();
  private final AtomicReference<String> lastStartedWorkflowType = new AtomicReference<>();
  private String taskQueue;
  private Worker worker;
  private TestActivities.TestActivitiesImpl activitiesImpl;
  private WorkflowClient workflowClient;
  private TracingWorkerInterceptor tracer;
  private WorkerFactory workerFactory;
  private static ScheduledExecutorService scheduledExecutor;

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
    if (SDKTestWorkflowRule.REGENERATE_JSON_FILES && !SDKTestWorkflowRule.useExternalService) {
      throw new IllegalStateException(
          "SDKTestWorkflowRule.REGENERATE_JSON_FILES is true when SDKTestWorkflowRule.useExternalService is false");
    }
    if (SDKTestWorkflowRule.useExternalService) {
      service =
          WorkflowServiceStubs.newInstance(
              WorkflowServiceStubsOptions.newBuilder().setTarget(serviceAddress).build());
    }
  }

  @AfterClass
  public static void closeService() {
    if (SDKTestWorkflowRule.useExternalService) {
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
                  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
                      WorkflowClientCallsInterceptor next) {
                    return new WorkflowClientCallsInterceptorBase(next) {
                      @Override
                      public WorkflowStartOutput start(WorkflowStartInput input) {
                        lastStartedWorkflowType.set(input.getWorkflowType());
                        return super.start(input);
                      }

                      @Override
                      public WorkflowSignalWithStartOutput signalWithStart(
                          WorkflowSignalWithStartInput input) {
                        lastStartedWorkflowType.set(
                            input.getWorkflowStartInput().getWorkflowType());
                        return super.signalWithStart(input);
                      }
                    };
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
    if (SDKTestWorkflowRule.useExternalService) {
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

    TestOptions.newWorkflowOptionsWithTimeouts(taskQueue);

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
    if (SDKTestWorkflowRule.useExternalService) {
      workerFactory.start();
    } else {
      testEnvironment.start();
    }
  }

  private void startWorkerFor(Class<?>... workflowTypes) {
    worker.registerWorkflowImplementationTypes(workflowTypes);
    if (SDKTestWorkflowRule.useExternalService) {
      workerFactory.start();
    } else {
      testEnvironment.start();
    }
  }

  static void sleep(Duration d) {
    if (SDKTestWorkflowRule.useExternalService) {
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
    if (SDKTestWorkflowRule.useExternalService) {
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestMultipleTimers.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
    String result = workflowStub.execute(taskQueue);
    assertEquals("heartbeatValue", result);
  }

  @Test
  public void testSyncUntypedAndStackTrace() {
    startWorkerFor(TestSyncWorkflowImpl.class);
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            "TestWorkflow1", TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            "TestWorkflow1", TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            "TestWorkflow1", TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            "TestWorkflow1", TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            "TestWorkflow1", TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            "TestWorkflow1", TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    if (SDKTestWorkflowRule.useExternalService) {
      w2 = workerFactory.newWorker(continuedTaskQueue);
    } else {
      w2 = testEnvironment.newWorker(continuedTaskQueue);
    }
    w2.registerWorkflowImplementationTypes(TestContinueAsNewImpl.class);
    startWorkerFor(TestContinueAsNewImpl.class);

    TestContinueAsNew client =
        workflowClient.newWorkflowStub(
            TestContinueAsNew.class, TestOptions.newWorkflowOptionsWithTimeouts(this.taskQueue));
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
            NoArgsWorkflow.class, TestOptions.newWorkflowOptionsWithTimeouts(this.taskQueue));
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
        TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
            .toBuilder()
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

    if (!SDKTestWorkflowRule.useExternalService) {
      // Use worker that polls on a task queue configured through @WorkflowMethod annotation of
      // func1
      assertResult(1, WorkflowClient.start(stubF1::func1, 1));
      assertEquals(1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    }
    // Check that duplicated start is not allowed for AllowDuplicate IdReusePolicy
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
        workflowClient.newWorkflowStub(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
                .toBuilder()
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
          TestOptions.newWorkflowOptionsWithTimeouts(taskQueue).toBuilder().setMemo(memo).build();
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
          TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
              .toBuilder()
              .setSearchAttributes(searchAttr)
              .build();
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
    WorkflowOptions workflowOptions = TestOptions.newWorkflowOptionsWithTimeouts(taskQueue);
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
        TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
            .toBuilder()
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
        TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
            .toBuilder()
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
    if (SDKTestWorkflowRule.useExternalService) {
      options = TestOptions.newWorkflowOptionsWithTimeouts(taskQueue);
    } else {
      options =
          TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
              .toBuilder()
              .setWorkflowRunTimeout(Duration.ofDays(1))
              .build();
    }
    TestWorkflows.TestWorkflow2 client =
        workflowClient.newWorkflowStub(TestWorkflows.TestWorkflow2.class, options);
    String result = client.execute(SDKTestWorkflowRule.useExternalService);
    assertEquals("testTimer", result);
    if (SDKTestWorkflowRule.useExternalService) {
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
            TestExceptionPropagation.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    QueryableWorkflow client =
        workflowClient.newWorkflowStub(
            QueryableWorkflow.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
    WorkflowClient.start(client::execute);
    sleep(Duration.ofSeconds(1));
    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    int queryCount = 100;
    for (int i = 0; i < queryCount; i++) {
      assertEquals("some state", client.getState());
      if (SDKTestWorkflowRule.useExternalService) {
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

  public static class TestChild implements ITestChild {

    @Override
    public String execute(String arg, int delay) {
      Workflow.sleep(delay);
      return arg.toUpperCase();
    }
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

  public static class AngryChild implements ITestChild {

    @Override
    public String execute(String taskQueue, int delay) {
      TestActivities.AngryChildActivity activity =
          Workflow.newActivityStub(
              TestActivities.AngryChildActivity.class,
              ActivityOptions.newBuilder()
                  .setTaskQueue(taskQueue)
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      activity.execute();
      throw ApplicationFailure.newFailure("simulated failure", "test");
    }
  }

  @WorkflowInterface
  public interface SignalingChild {

    @WorkflowMethod
    String execute(String arg, String parentWorkflowId);
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
                .toBuilder()
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
                .toBuilder()
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
                .toBuilder()
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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

  public static class TestCronParentWorkflow implements TestWorkflows.TestWorkflow1 {

    private final TestWorkflowWithCronSchedule cronChild =
        Workflow.newChildWorkflowStub(TestWorkflowWithCronSchedule.class);

    @Override
    public String execute(String taskQueue) {
      return cronChild.execute(taskQueue);
    }
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    startWorkerFor(TestGetVersionSameIdOnReplay.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    startWorkerFor(TestGetVersionSameId.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    startWorkerFor(TestGetVersionWorkflowAddNewBefore.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    startWorkerFor(TestGetVersionWorkflowReplaceGetVersionId.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    startWorkerFor(TestGetVersionWorkflowReplaceCompletely.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    startWorkerFor(TestGetVersionWorkflowRemove.class);
    TestWorkflows.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            TestWorkflows.TestWorkflow1.class,
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestWorkflowSignaled.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));

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
    if (SDKTestWorkflowRule.useExternalService) {
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            GenericParametersWorkflow.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
                .toBuilder()
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
            TestSagaWorkflow.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
            TestSagaWorkflow.class, TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
    sagaWorkflow.execute(taskQueue, true);
    String trace = tracer.getTrace();
    assertTrue(trace, trace.contains("executeChildWorkflow TestCompensationWorkflow"));
    assertTrue(trace, trace.contains("executeActivity Activity2"));
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
            TestOptions.newWorkflowOptionsWithTimeouts(taskQueue));
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
        TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
            .toBuilder()
            .setWorkflowId(workflowId)
            .build();
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
        TestOptions.newWorkflowOptionsWithTimeouts(taskQueue)
            .toBuilder()
            .setWorkflowId(workflowId)
            .build();
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
    WorkflowOptions options = TestOptions.newWorkflowOptionsWithTimeouts(taskQueue);
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
}
