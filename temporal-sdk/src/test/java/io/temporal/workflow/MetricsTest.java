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

package io.temporal.workflow;

import static io.temporal.serviceclient.MetricsType.TEMPORAL_LONG_REQUEST;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_REQUEST;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_REQUEST_FAILURE;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_REQUEST_LATENCY;
import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static io.temporal.worker.MetricsType.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.*;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.TestActivity3;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.ReceiveSignalObjectWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class MetricsTest {

  private static final long REPORTING_FLUSH_TIME = 600;
  private static final String TASK_QUEUE = "metrics_test";
  private TestWorkflowEnvironment testEnvironment;
  private TestStatsReporter reporter;

  private static final Map<String, String> TAGS_NAMESPACE =
      new ImmutableMap.Builder<String, String>().putAll(MetricsTag.defaultTags(NAMESPACE)).build();

  private static final Map<String, String> TAGS_TASK_QUEUE =
      new ImmutableMap.Builder<String, String>()
          .putAll(MetricsTag.defaultTags(NAMESPACE))
          .put(MetricsTag.TASK_QUEUE, TASK_QUEUE)
          .build();

  private static final Map<String, String> TAGS_STICKY_TASK_QUEUE =
      new ImmutableMap.Builder<String, String>()
          .putAll(MetricsTag.defaultTags(NAMESPACE))
          .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.WORKFLOW_WORKER.getValue())
          .put(MetricsTag.TASK_QUEUE, TASK_QUEUE + ":sticky")
          .build();

  private static final Map<String, String> TAGS_LOCAL_ACTIVITY_WORKER =
      new ImmutableMap.Builder<String, String>()
          .putAll(TAGS_TASK_QUEUE)
          .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.LOCAL_ACTIVITY_WORKER.getValue())
          .build();

  private static final Map<String, String> TAGS_ACTIVITY_WORKER =
      new ImmutableMap.Builder<String, String>()
          .putAll(TAGS_TASK_QUEUE)
          .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.ACTIVITY_WORKER.getValue())
          .build();

  private static final Map<String, String> TAGS_WORKFLOW_WORKER =
      new ImmutableMap.Builder<String, String>()
          .putAll(TAGS_TASK_QUEUE)
          .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.WORKFLOW_WORKER.getValue())
          .build();

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnvironment != null) {
            System.err.println("HISTORIES:\n" + testEnvironment.getDiagnostics());
          }
        }
      };

  public void setUp(WorkerFactoryOptions workerFactoryOptions) {
    Scope metricsScope;
    reporter = new TestStatsReporter();
    metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(10));

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setMetricsScope(metricsScope)
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build())
            .setWorkerFactoryOptions(workerFactoryOptions)
            .build();

    testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testWorkerMetrics() throws InterruptedException {
    setUp(WorkerFactoryOptions.getDefaultInstance());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        TestCustomMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    testEnvironment.start();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(TASK_QUEUE)
            .build();
    NoArgsWorkflow workflow = workflowClient.newWorkflowStub(NoArgsWorkflow.class, options);
    workflow.execute();

    Thread.sleep(REPORTING_FLUSH_TIME);

    reporter.assertCounter("temporal_worker_start", TAGS_WORKFLOW_WORKER, 1);
    reporter.assertCounter("temporal_worker_start", TAGS_ACTIVITY_WORKER, 1);
    reporter.assertCounter("temporal_worker_start", TAGS_LOCAL_ACTIVITY_WORKER, 1);

    reporter.assertCounter("temporal_poller_start", TAGS_WORKFLOW_WORKER, 2);
    reporter.assertCounter("temporal_poller_start", TAGS_ACTIVITY_WORKER, 5);
    reporter.assertCounter("temporal_poller_start", TAGS_LOCAL_ACTIVITY_WORKER, 1);
    // sticky
    reporter.assertCounter("temporal_poller_start", TAGS_STICKY_TASK_QUEUE, 10);
  }

  @Test
  public void testWorkflowMetrics() throws InterruptedException {
    setUp(WorkerFactoryOptions.getDefaultInstance());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        TestCustomMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    testEnvironment.start();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(TASK_QUEUE)
            .build();
    NoArgsWorkflow workflow = workflowClient.newWorkflowStub(NoArgsWorkflow.class, options);
    workflow.execute();

    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> pollActivityTaskQueueTag =
        new ImmutableMap.Builder<String, String>()
            .putAll(TAGS_ACTIVITY_WORKER)
            .put(MetricsTag.OPERATION_NAME, "PollActivityTaskQueue")
            .build();

    reporter.assertCounter(TEMPORAL_LONG_REQUEST, pollActivityTaskQueueTag);

    Map<String, String> pollWorkflowTaskQueueTag =
        new ImmutableMap.Builder<String, String>()
            .putAll(TAGS_WORKFLOW_WORKER)
            .put(MetricsTag.OPERATION_NAME, "PollWorkflowTaskQueue")
            .build();

    reporter.assertCounter(TEMPORAL_LONG_REQUEST, pollWorkflowTaskQueueTag);

    Map<String, String> workflowTags = new LinkedHashMap<>(TAGS_TASK_QUEUE);

    workflowTags.put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
    reporter.assertCounter("test_started", workflowTags, 1);
    reporter.assertCounter("test_done", workflowTags, 1);

    workflowTags.put(MetricsTag.WORKFLOW_TYPE, "TestChildWorkflow");
    reporter.assertCounter("test_child_started", workflowTags, 1);
    reporter.assertCounter("test_child_done", workflowTags, 1);
    reporter.assertTimerMinDuration("test_timer", workflowTags, Duration.ofSeconds(3));

    Map<String, String> activityCompletionTags =
        new ImmutableMap.Builder<String, String>()
            .putAll(TAGS_ACTIVITY_WORKER)
            .put(MetricsTag.ACTIVITY_TYPE, "Execute")
            .put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow")
            .put(MetricsTag.OPERATION_NAME, "RespondActivityTaskCompleted")
            .build();
    reporter.assertCounter(TEMPORAL_REQUEST, activityCompletionTags, 1);

    workflowTags.put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
    workflowTags.put(MetricsTag.OPERATION_NAME, "StartWorkflowExecution");

    reporter.assertCounter(TEMPORAL_REQUEST, workflowTags, 1);
    reporter.assertTimer(TEMPORAL_REQUEST_LATENCY, workflowTags);

    Map<String, String> workflowTaskCompletionTags =
        new ImmutableMap.Builder<String, String>()
            .putAll(TAGS_WORKFLOW_WORKER)
            .put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow")
            .put(MetricsTag.OPERATION_NAME, "RespondWorkflowTaskCompleted")
            .build();
    reporter.assertCounter(TEMPORAL_REQUEST, workflowTaskCompletionTags, 4);
  }

  @Test
  public void testCorruptedSignalMetrics() throws InterruptedException {
    setUp(
        WorkerFactoryOptions.newBuilder()
            .setWorkerInterceptors(
                new CorruptedSignalWorkerInterceptor(),
                // Add noop just to test that list of interceptors is working.
                new WorkerInterceptorBase())
            .build());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);

    worker.registerWorkflowImplementationTypes(
        SendSignalObjectWorkflowImpl.class, ReceiveSignalObjectWorkflowImpl.class);
    testEnvironment.start();

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(TASK_QUEUE)
            .build();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    TestWorkflowReturnString workflow =
        workflowClient.newWorkflowStub(TestWorkflowReturnString.class, options);
    workflow.execute();

    // Wait for reporter
    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> tags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
            put(MetricsTag.WORKFLOW_TYPE, "ReceiveSignalObjectWorkflow");
          }
        };
    reporter.assertCounter(CORRUPTED_SIGNALS_COUNTER, tags, 1);
  }

  @Test
  public void testTemporalFailureMetric() throws InterruptedException {
    setUp(
        WorkerFactoryOptions.newBuilder()
            .setWorkerInterceptors(new CorruptedSignalWorkerInterceptor())
            .build());

    try {
      WorkflowServiceStubs serviceStubs =
          testEnvironment.getWorkflowClient().getWorkflowServiceStubs();

      serviceStubs.blockingStub().describeNamespace(DescribeNamespaceRequest.newBuilder().build());
      fail("failure expected");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    }

    // Wait for reporter
    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> tags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(MetricsTag.DEFAULT_VALUE));
            put(MetricsTag.OPERATION_NAME, "DescribeNamespace");
          }
        };
    reporter.assertCounter(TEMPORAL_REQUEST, tags, 1);
    tags.put(MetricsTag.STATUS_CODE, "INVALID_ARGUMENT");
    reporter.assertCounter(TEMPORAL_REQUEST_FAILURE, tags, 1);
  }

  @Test
  public void testTemporalActivityFailureMetric() throws InterruptedException {
    setUp(WorkerFactoryOptions.getDefaultInstance());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestActivityFailureCountersWorkflow.class);
    worker.registerActivitiesImplementations(new TestActivitiesImpl());
    testEnvironment.start();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(TASK_QUEUE)
            .build();
    NoArgsWorkflow workflow = workflowClient.newWorkflowStub(NoArgsWorkflow.class, options);
    workflow.execute();

    // Wait for reporter
    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> activityTags =
        new ImmutableMap.Builder<String, String>()
            .putAll(TAGS_ACTIVITY_WORKER)
            .put(MetricsTag.ACTIVITY_TYPE, "ThrowIO")
            .put(MetricsTag.EXCEPTION, "IOException")
            .put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow")
            .build();

    Map<String, String> localActivityTags =
        new ImmutableMap.Builder<String, String>()
            .putAll(activityTags)
            .put(
                MetricsTag.WORKER_TYPE,
                WorkerMetricsTag.WorkerType.LOCAL_ACTIVITY_WORKER.getValue())
            .build();

    reporter.assertCounter(ACTIVITY_EXEC_FAILED_COUNTER, activityTags, 2);
    reporter.assertCounter(LOCAL_ACTIVITY_EXEC_FAILED_COUNTER, localActivityTags, 3);
  }

  @Test
  public void testTemporalInvalidRequestMetric() throws InterruptedException {
    setUp(
        WorkerFactoryOptions.newBuilder()
            .setWorkerInterceptors(new CorruptedSignalWorkerInterceptor())
            .build());

    try {
      WorkflowServiceStubs serviceStubs =
          testEnvironment.getWorkflowClient().getWorkflowServiceStubs();

      serviceStubs
          .blockingStub()
          .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder().build());
      fail("failure expected");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    }

    // Wait for reporter
    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> tags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(MetricsTag.DEFAULT_VALUE));
            put(MetricsTag.OPERATION_NAME, "StartWorkflowExecution");
          }
        };
    reporter.assertCounter(TEMPORAL_REQUEST, tags, 1);

    tags.put(MetricsTag.STATUS_CODE, "INVALID_ARGUMENT");
    reporter.assertCounter(TEMPORAL_REQUEST_FAILURE, tags, 1);
  }

  @Test
  public void testStickyCacheSize() throws InterruptedException, ExecutionException {
    setUp(WorkerFactoryOptions.getDefaultInstance());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestWorkflowWithSleep.class);
    testEnvironment.start();

    Thread.sleep(REPORTING_FLUSH_TIME);
    reporter.assertGauge(STICKY_CACHE_SIZE, TAGS_NAMESPACE, 0);

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(7000))
            .setTaskQueue(TASK_QUEUE)
            .build();
    NoArgsWorkflow workflow = workflowClient.newWorkflowStub(NoArgsWorkflow.class, options);
    CompletableFuture<Void> wfFuture = WorkflowClient.execute(workflow::execute);
    SDKTestWorkflowRule.waitForOKQuery(WorkflowStub.fromTyped(workflow));

    Thread.sleep(REPORTING_FLUSH_TIME);
    reporter.assertGauge(STICKY_CACHE_SIZE, TAGS_NAMESPACE, 1);
    reporter.assertGauge(WORKFLOW_ACTIVE_THREAD_COUNT, TAGS_NAMESPACE, val -> val == 1 || val == 2);

    wfFuture.get();

    Thread.sleep(REPORTING_FLUSH_TIME);
    reporter.assertGauge(STICKY_CACHE_SIZE, TAGS_NAMESPACE, 0);
    reporter.assertGauge(WORKFLOW_ACTIVE_THREAD_COUNT, TAGS_NAMESPACE, 0);
  }

  @WorkflowInterface
  public interface TestChildWorkflow {

    @WorkflowMethod
    void executeChild();
  }

  public static class TestActivityFailureCountersWorkflow implements NoArgsWorkflow {

    @Override
    public void execute() {
      ActivityOptions activityOptions =
          ActivityOptions.newBuilder()
              .setTaskQueue(TASK_QUEUE)
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
              .build();
      VariousTestActivities activity =
          Workflow.newActivityStub(VariousTestActivities.class, activityOptions);
      try {
        activity.throwIO();
      } catch (Exception e) {
        // increment temporal_activity_execution_failed
      }

      LocalActivityOptions localActivityOptions =
          LocalActivityOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build();
      VariousTestActivities localActivity =
          Workflow.newLocalActivityStub(VariousTestActivities.class, localActivityOptions);
      try {
        localActivity.throwIO();
      } catch (Exception e) {
        // increment temporal_local_activity_failed
      }
    }
  }

  public static class TestCustomMetricsInWorkflow implements NoArgsWorkflow {

    @Override
    public void execute() {
      Workflow.getMetricsScope().counter("test_started").inc(1);

      ActivityOptions activityOptions =
          ActivityOptions.newBuilder()
              .setTaskQueue(TASK_QUEUE)
              .setScheduleToCloseTimeout(Duration.ofSeconds(100))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .setDoNotRetry(AssertionError.class.getName())
                      .build())
              .build();
      TestActivity3 activity = Workflow.newActivityStub(TestActivity3.class, activityOptions);
      activity.execute(1);

      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
      TestChildWorkflow workflow = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      workflow.executeChild();

      Workflow.getMetricsScope().counter("test_done").inc(1);
    }
  }

  static class TestActivityImpl implements TestActivity3 {

    @Override
    public int execute(int input) {
      return input;
    }
  }

  public static class TestMetricsInChildWorkflow implements TestChildWorkflow {

    @Override
    public void executeChild() {
      Workflow.getMetricsScope().counter("test_child_started").inc(1);

      Stopwatch sw = Workflow.getMetricsScope().timer("test_timer").start();
      Workflow.sleep(3000);
      sw.stop();

      Workflow.getMetricsScope().counter("test_child_done").inc(1);
    }
  }

  public static class ReceiveSignalObjectWorkflowImpl implements ReceiveSignalObjectWorkflow {

    // Keep workflow open so that we can send signal
    CompletablePromise<Void> promise = Workflow.newPromise();
    private String receivedSignal = "Initial State";

    @Override
    public String execute() {
      promise.get();
      return receivedSignal;
    }

    @Override
    public void signal(Signal arg) {
      receivedSignal = arg.value;
    }

    @Override
    public void close() {
      promise.complete(null);
    }
  }

  public static class SendSignalObjectWorkflowImpl implements TestWorkflowReturnString {

    @Override
    public String execute() {
      ReceiveSignalObjectWorkflow child =
          Workflow.newChildWorkflowStub(ReceiveSignalObjectWorkflow.class);
      Promise<String> greeting = Async.function(child::execute);
      Signal sig = new Signal();
      sig.value = "Hello World";
      child.signal(sig);
      child.close();
      return greeting.get();
    }
  }

  public static class TestWorkflowWithSleep implements NoArgsWorkflow {

    @Override
    public void execute() {
      Workflow.sleep(5000);
    }
  }

  public static class Signal {
    public String value;
  }

  private static class CorruptedSignalWorkerInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          next.init(
              new SignalWorkflowOutboundCallsInterceptor(
                  args -> {
                    if (args != null && args.length > 0) {
                      return new Object[] {"Corrupted Signal"};
                    }
                    return args;
                  },
                  sig -> sig,
                  outboundCalls));
        }
      };
    }
  }

  private static class SignalWorkflowOutboundCallsInterceptor
      extends WorkflowOutboundCallsInterceptorBase {
    private final Function<Object[], Object[]> overrideArgs;
    private final Function<String, String> overrideSignalName;

    public SignalWorkflowOutboundCallsInterceptor(
        Function<Object[], Object[]> overrideArgs,
        Function<String, String> overrideSignalName,
        WorkflowOutboundCallsInterceptor next) {
      super(next);
      this.overrideArgs = overrideArgs;
      this.overrideSignalName = overrideSignalName;
    }

    @Override
    public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
      Object[] args = input.getArgs();
      if (args != null && args.length > 0) {
        args = new Object[] {"corrupted signal"};
      }
      return super.signalExternalWorkflow(
          new SignalExternalInput(
              input.getExecution(),
              overrideSignalName.apply(input.getSignalName()),
              overrideArgs.apply(args)));
    }
  }
}
