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

import static io.temporal.internal.metrics.MetricsType.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.interceptors.SignalWorkflowOutboundCallsInterceptor;
import io.temporal.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.workflowservice.v1.StartWorkflowExecutionRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;

public class MetricsTest {

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

  private static final com.uber.m3.util.Duration REPORTING_FREQUENCY =
      com.uber.m3.util.Duration.ofMillis(10);
  private static final long REPORTING_FLUSH_TIME = 600;
  private static final String TASK_QUEUE = "metrics-test";

  private TestWorkflowEnvironment testEnvironment;
  private StatsReporter reporter;

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    void execute();
  }

  public static class TestMetricsInWorkflow implements TestWorkflow {

    @Override
    public void execute() {
      Workflow.getMetricsScope().counter("test-started").inc(1);

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
      TestActivity activity = Workflow.newActivityStub(TestActivity.class, activityOptions);
      activity.runActivity(1);

      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
      TestChildWorkflow workflow = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      workflow.executeChild();

      Workflow.getMetricsScope().counter("test-done").inc(1);
    }
  }

  @ActivityInterface
  public interface TestActivity {
    int runActivity(int input);
  }

  static class TestActivityImpl implements TestActivity {
    @Override
    public int runActivity(int input) {
      return input;
    }
  }

  @WorkflowInterface
  public interface TestChildWorkflow {

    @WorkflowMethod
    void executeChild();
  }

  public static class TestMetricsInChildWorkflow implements TestChildWorkflow {

    @Override
    public void executeChild() {
      Workflow.getMetricsScope().counter("test-child-started").inc(1);

      Stopwatch sw = Workflow.getMetricsScope().timer("test-timer").start();
      Workflow.sleep(3000);
      sw.stop();

      Workflow.getMetricsScope().counter("test-child-done").inc(1);
    }
  }

  @WorkflowInterface
  public interface ReceiveSignalObjectChildWorkflow {

    @WorkflowMethod
    String execute();

    @SignalMethod(name = "testSignal")
    void signal(Signal arg);

    @SignalMethod(name = "endWorkflow")
    void close();
  }

  public static class ReceiveSignalObjectChildWorkflowImpl
      implements ReceiveSignalObjectChildWorkflow {
    private String receivedSignal = "Initial State";
    // Keep workflow open so that we can send signal
    CompletablePromise<Void> promise = Workflow.newPromise();

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

  @WorkflowInterface
  public interface SendSignalObjectWorkflow {

    @WorkflowMethod
    String execute();
  }

  public static class SendSignalObjectWorkflowImpl implements SendSignalObjectWorkflow {
    @Override
    public String execute() {
      ReceiveSignalObjectChildWorkflow child =
          Workflow.newChildWorkflowStub(ReceiveSignalObjectChildWorkflow.class);
      Promise<String> greeting = Async.function(child::execute);
      Signal sig = new Signal();
      sig.value = "Hello World";
      child.signal(sig);
      child.close();
      return greeting.get();
    }
  }

  public static class Signal {

    public String value;
  }

  public void setUp(
      com.uber.m3.util.Duration reportingFrequecy, WorkerFactoryOptions workerFactoryOptions) {
    reporter = mock(StatsReporter.class);
    Scope scope = new RootScopeBuilder().reporter(reporter).reportEvery(reportingFrequecy);

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setMetricsScope(scope)
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setNamespace(WorkflowTest.NAMESPACE).build())
            .setWorkerFactoryOptions(workerFactoryOptions)
            .build();

    testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testWorkflowMetrics() throws InterruptedException {
    setUp(REPORTING_FREQUENCY, WorkerFactoryOptions.getDefaultInstance());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        TestMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    testEnvironment.start();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(TASK_QUEUE)
            .build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
    workflow.execute();

    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, WorkflowTest.NAMESPACE)
            .put(MetricsTag.TASK_QUEUE, TASK_QUEUE)
            .build();

    verify(reporter, times(1)).reportCounter("test-started", tags, 1);
    verify(reporter, times(1)).reportCounter("test-done", tags, 1);
    verify(reporter, times(1)).reportCounter("test-child-started", tags, 1);
    verify(reporter, times(1)).reportCounter("test-child-done", tags, 1);

    ArgumentCaptor<com.uber.m3.util.Duration> sleepDurationCaptor =
        ArgumentCaptor.forClass(com.uber.m3.util.Duration.class);
    verify(reporter, times(1)).reportTimer(eq("test-timer"), any(), sleepDurationCaptor.capture());

    com.uber.m3.util.Duration sleepDuration = sleepDurationCaptor.getValue();
    assertTrue(
        sleepDuration.toString(),
        sleepDuration.compareTo(com.uber.m3.util.Duration.ofSeconds(3)) >= 0);

    Map<String, String> activityCompletionTags =
        new ImmutableMap.Builder<String, String>(3)
            .put(MetricsTag.NAMESPACE, WorkflowTest.NAMESPACE)
            .put(MetricsTag.TASK_QUEUE, TASK_QUEUE)
            .put(MetricsTag.ACTIVITY_TYPE, "RunActivity")
            .put(MetricsTag.WORKFLOW_TYPE, "TestWorkflow")
            .build();
    verify(reporter, times(1))
        .reportCounter(ACTIVITY_TASK_COMPLETED_COUNTER, activityCompletionTags, 1);
    verify(reporter, atLeastOnce())
        .reportCounter(
            TEMPORAL_METRICS_PREFIX + "StartWorkflowExecution." + TEMPORAL_REQUEST,
            new HashMap<>(),
            1);
    verify(reporter, atLeastOnce())
        .reportTimer(
            eq(TEMPORAL_METRICS_PREFIX + "StartWorkflowExecution." + TEMPORAL_LATENCY),
            eq(new HashMap<>()),
            any());
    verify(reporter, atLeastOnce())
        .reportCounter(
            eq(TEMPORAL_METRICS_PREFIX + "PollForDecisionTask." + TEMPORAL_REQUEST),
            eq(new HashMap<>()),
            anyLong());
    verify(reporter, atLeastOnce())
        .reportCounter(
            eq(TEMPORAL_METRICS_PREFIX + "RespondDecisionTaskCompleted." + TEMPORAL_REQUEST),
            eq(new HashMap<>()),
            anyLong());
    verify(reporter, atLeastOnce())
        .reportCounter(
            eq(TEMPORAL_METRICS_PREFIX + "PollForActivityTask." + TEMPORAL_REQUEST),
            eq(new HashMap<>()),
            anyLong());
    verify(reporter, atLeastOnce())
        .reportCounter(
            eq(TEMPORAL_METRICS_PREFIX + "RespondActivityTaskCompleted." + TEMPORAL_REQUEST),
            eq(new HashMap<>()),
            anyLong());
  }

  @Test
  public void testCorruptedSignalMetrics() throws InterruptedException {
    setUp(
        REPORTING_FREQUENCY,
        WorkerFactoryOptions.newBuilder()
            .setWorkflowInterceptors(
                new CorruptedSignalWorkflowInterceptor(),
                // Add noop just to test that list of interceptors is working.
                next -> new WorkflowInboundCallsInterceptorBase(next))
            .build());

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);

    worker.registerWorkflowImplementationTypes(
        SendSignalObjectWorkflowImpl.class, ReceiveSignalObjectChildWorkflowImpl.class);
    testEnvironment.start();

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1000))
            .setTaskQueue(TASK_QUEUE)
            .build();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    SendSignalObjectWorkflow workflow =
        workflowClient.newWorkflowStub(SendSignalObjectWorkflow.class, options);
    workflow.execute();

    // Wait for reporter
    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, WorkflowTest.NAMESPACE)
            .put(MetricsTag.TASK_QUEUE, TASK_QUEUE)
            .build();
    verify(reporter, times(1)).reportCounter(MetricsType.CORRUPTED_SIGNALS_COUNTER, tags, 1);
    testEnvironment.close();
  }

  private static class CorruptedSignalWorkflowInterceptor implements WorkflowInterceptor {

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

  @Test
  public void testTemporalFailureMetric() throws InterruptedException {
    setUp(
        REPORTING_FREQUENCY,
        WorkerFactoryOptions.newBuilder()
            .setWorkflowInterceptors(new CorruptedSignalWorkflowInterceptor())
            .build());

    try {
      WorkflowServiceStubs serviceStubs =
          testEnvironment.getWorkflowClient().getWorkflowServiceStubs();

      serviceStubs.blockingStub().describeNamespace(DescribeNamespaceRequest.newBuilder().build());
      fail("failure expected");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.UNIMPLEMENTED, e.getStatus().getCode());
    }

    // Wait for reporter
    Thread.sleep(REPORTING_FLUSH_TIME);

    verify(reporter, times(1))
        .reportCounter("temporal-DescribeNamespace.temporal-request", new HashMap<>(), 1);
    verify(reporter, times(1))
        .reportCounter("temporal-DescribeNamespace.temporal-error", new HashMap<>(), 1);
    testEnvironment.close();
  }

  @Test
  public void testTemporalInvalidRequestMetric() throws InterruptedException {
    setUp(
        com.uber.m3.util.Duration.ofMillis(300),
        WorkerFactoryOptions.newBuilder()
            .setWorkflowInterceptors(new CorruptedSignalWorkflowInterceptor())
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

    verify(reporter, times(1))
        .reportCounter("temporal-StartWorkflowExecution.temporal-request", new HashMap<>(), 1);
    verify(reporter, times(1))
        .reportCounter(
            "temporal-StartWorkflowExecution.temporal-invalid-request", new HashMap<>(), 1);
    testEnvironment.close();
  }
}
