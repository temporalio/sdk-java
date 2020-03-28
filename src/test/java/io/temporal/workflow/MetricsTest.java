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

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.util.ImmutableMap;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.BaseWorkflowInvoker;
import io.temporal.common.interceptors.WorkflowCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInterceptor;
import io.temporal.common.interceptors.WorkflowInvocationInterceptor;
import io.temporal.common.interceptors.WorkflowInvoker;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.interceptors.SignalWorkflowCallsInterceptor;
import java.time.Duration;
import java.util.Map;
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

  private static final String taskList = "metrics-test";
  private TestWorkflowEnvironment testEnvironment;
  private StatsReporter reporter;

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
              .setTaskList(taskList)
              .setScheduleToCloseTimeout(Duration.ofSeconds(1))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setExpiration(Duration.ofSeconds(100))
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .setDoNotRetry(AssertionError.class)
                      .build())
              .build();
      TestActivity activity = Workflow.newActivityStub(TestActivity.class, activityOptions);
      activity.runActivity(1);

      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setTaskList(taskList).build();
      TestChildWorkflow workflow = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      workflow.executeChild();

      Workflow.getMetricsScope().counter("test-done").inc(1);
    }
  }

  public interface TestActivity {
    int runActivity(int input);
  }

  static class TestActivityImpl implements TestActivity {
    @Override
    public int runActivity(int input) {
      return input;
    }
  }

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
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setMetricsScope(scope)
                    .setNamespace(WorkflowTest.NAMESPACE)
                    .build())
            .setWorkerFactoryOptions(workerFactoryOptions)
            .build();

    testEnvironment = TestWorkflowEnvironment.newInstance(testOptions);
  }

  @Test
  public void testWorkflowMetrics() throws InterruptedException {
    setUp(com.uber.m3.util.Duration.ofMillis(10), WorkerFactoryOptions.getDefaultInstance());

    Worker worker = testEnvironment.newWorker(taskList);
    worker.registerWorkflowImplementationTypes(
        TestMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    testEnvironment.start();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
            .setTaskList(taskList)
            .build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
    workflow.execute();

    Thread.sleep(200);

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, WorkflowTest.NAMESPACE)
            .put(MetricsTag.TASK_LIST, taskList)
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
    assertTrue(
        sleepDuration.toString(),
        sleepDuration.compareTo(com.uber.m3.util.Duration.ofMillis(3100)) < 0);

    Map<String, String> activityCompletionTags =
        new ImmutableMap.Builder<String, String>(3)
            .put(MetricsTag.NAMESPACE, WorkflowTest.NAMESPACE)
            .put(MetricsTag.TASK_LIST, taskList)
            .put(MetricsTag.ACTIVITY_TYPE, "TestActivity_runActivity")
            .put(MetricsTag.WORKFLOW_TYPE, "TestWorkflow_execute")
            .build();
    verify(reporter, times(1))
        .reportCounter("temporal-activity-task-completed", activityCompletionTags, 1);

    testEnvironment.close();
  }

  @Test
  public void testCorruptedSignalMetrics() throws InterruptedException {
    setUp(
        com.uber.m3.util.Duration.ofMillis(300),
        WorkerFactoryOptions.newBuilder()
            .setWorkflowInterceptor(new CorruptedSignalWorkflowInterceptor())
            .build());

    Worker worker = testEnvironment.newWorker(taskList);

    worker.registerWorkflowImplementationTypes(
        SendSignalObjectWorkflowImpl.class, ReceiveSignalObjectChildWorkflowImpl.class);
    testEnvironment.start();

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
            .setTaskList(taskList)
            .build();

    WorkflowClient workflowClient = testEnvironment.getWorkflowClient();
    SendSignalObjectWorkflow workflow =
        workflowClient.newWorkflowStub(SendSignalObjectWorkflow.class, options);
    workflow.execute();

    // Wait for reporter
    Thread.sleep(600);

    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, WorkflowTest.NAMESPACE)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    verify(reporter, times(1)).reportCounter(MetricsType.CORRUPTED_SIGNALS_COUNTER, tags, 1);
    testEnvironment.close();
  }

  private static class CorruptedSignalWorkflowInterceptor implements WorkflowInterceptor {

    @Override
    public WorkflowInvoker interceptExecuteWorkflow(
        WorkflowCallsInterceptor interceptor, WorkflowInvocationInterceptor next) {
      SignalWorkflowCallsInterceptor i =
          new SignalWorkflowCallsInterceptor(
              args -> {
                if (args != null && args.length > 0) {
                  return new Object[] {"Corrupted Signal"};
                }
                return args;
              },
              sig -> sig,
              interceptor);
      return new BaseWorkflowInvoker(i, next);
    }
  }
}
