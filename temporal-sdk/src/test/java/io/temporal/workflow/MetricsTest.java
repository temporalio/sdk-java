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

import static io.temporal.internal.metrics.MetricsType.ACTIVITY_EXEC_FAILED_COUNTER;
import static io.temporal.internal.metrics.MetricsType.CORRUPTED_SIGNALS_COUNTER;
import static io.temporal.internal.metrics.MetricsType.LOCAL_ACTIVITY_FAILED_COUNTER;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_LONG_REQUEST;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_REQUEST;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_REQUEST_FAILURE;
import static io.temporal.serviceclient.MetricsType.TEMPORAL_REQUEST_LATENCY;
import static io.temporal.workflow.shared.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.interceptors.SignalWorkflowOutboundCallsInterceptor;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.TestActivity3;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.ReceiveSignalObjectWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
  public void testWorkflowMetrics() throws InterruptedException {
    setUp(WorkerFactoryOptions.getDefaultInstance());

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
    NoArgsWorkflow workflow = workflowClient.newWorkflowStub(NoArgsWorkflow.class, options);
    workflow.execute();

    Thread.sleep(REPORTING_FLUSH_TIME);

    Map<String, String> tagsB =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
          }
        };
    reporter.assertCounter("temporal_worker_start", tagsB, 3);
    reporter.assertCounter("temporal_poller_start", tagsB);
    tagsB.put(MetricsTag.OPERATION_NAME, "PollActivityTaskQueue");
    reporter.assertCounter(TEMPORAL_LONG_REQUEST, tagsB);
    tagsB.put(MetricsTag.OPERATION_NAME, "PollWorkflowTaskQueue");
    reporter.assertCounter(TEMPORAL_LONG_REQUEST, tagsB);

    Map<String, String> tags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.TASK_QUEUE, "sticky");
          }
        };
    reporter.assertCounter("temporal_poller_start", tags);

    tags.put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
    tags.put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
    reporter.assertCounter("test_started", tags, 1);
    reporter.assertCounter("test_done", tags, 1);

    tags.put(MetricsTag.WORKFLOW_TYPE, "TestChildWorkflow");
    tags.put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
    reporter.assertCounter("test_child_started", tags, 1);
    reporter.assertCounter("test_child_done", tags, 1);
    reporter.assertTimerMinDuration("test_timer", tags, Duration.ofSeconds(3));

    Map<String, String> activityCompletionTags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
            put(MetricsTag.ACTIVITY_TYPE, "Execute");
            put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
            put(MetricsTag.OPERATION_NAME, "RespondActivityTaskCompleted");
          }
        };
    reporter.assertCounter(TEMPORAL_REQUEST, activityCompletionTags, 1);

    tagsB =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
            put(MetricsTag.OPERATION_NAME, "StartWorkflowExecution");
            put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
          }
        };
    reporter.assertCounter(TEMPORAL_REQUEST, tagsB, 1);
    reporter.assertTimer(TEMPORAL_REQUEST_LATENCY, tagsB);

    Map<String, String> workflowTaskCompletionTags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
            put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
            put(MetricsTag.OPERATION_NAME, "RespondWorkflowTaskCompleted");
          }
        };
    reporter.assertCounter(TEMPORAL_REQUEST, workflowTaskCompletionTags, 4);
  }

  @Test
  public void testCorruptedSignalMetrics() throws InterruptedException {
    setUp(
        WorkerFactoryOptions.newBuilder()
            .setWorkerInterceptors(
                new CorruptedSignalWorkerInterceptor(),
                // Add noop just to test that list of interceptors is working.
                new WorkerInterceptor() {
                  @Override
                  public WorkflowInboundCallsInterceptor interceptWorkflow(
                      WorkflowInboundCallsInterceptor next) {
                    return next;
                  }

                  @Override
                  public ActivityInboundCallsInterceptor interceptActivity(
                      ActivityInboundCallsInterceptor next) {
                    return next;
                  }
                })
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
      assertEquals(Status.Code.UNIMPLEMENTED, e.getStatus().getCode());
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
    tags.put(MetricsTag.STATUS_CODE, "UNIMPLEMENTED");
    reporter.assertCounter(TEMPORAL_REQUEST_FAILURE, tags, 1);
  }

  @Test
  public void testTemporalActivityFailureMetric() throws InterruptedException {
    setUp(
        WorkerFactoryOptions.newBuilder()
            .setWorkerInterceptors(
                // Add noop just to test that list of interceptors is working.
                new WorkerInterceptor() {
                  @Override
                  public WorkflowInboundCallsInterceptor interceptWorkflow(
                      WorkflowInboundCallsInterceptor next) {
                    return next;
                  }

                  @Override
                  public ActivityInboundCallsInterceptor interceptActivity(
                      ActivityInboundCallsInterceptor next) {
                    return next;
                  }
                })
            .build());

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

    Map<String, String> tags =
        new LinkedHashMap<String, String>() {
          {
            putAll(MetricsTag.defaultTags(NAMESPACE));
            put(MetricsTag.ACTIVITY_TYPE, "ThrowIO");
            put(MetricsTag.EXCEPTION, "IOException");
            put(MetricsTag.TASK_QUEUE, TASK_QUEUE);
            put(MetricsTag.WORKFLOW_TYPE, "NoArgsWorkflow");
          }
        };
    reporter.assertCounter(ACTIVITY_EXEC_FAILED_COUNTER, tags, 2);
    reporter.assertCounter(LOCAL_ACTIVITY_FAILED_COUNTER, tags, 3);
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

  public static class TestMetricsInWorkflow implements NoArgsWorkflow {

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

  public static class Signal {

    public String value;
  }

  private static class CorruptedSignalWorkerInterceptor implements WorkerInterceptor {

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

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
      return next;
    }
  }
}
