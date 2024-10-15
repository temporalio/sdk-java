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

package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowSlotGrpcInterceptedTests {
  private final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 100;
  private final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 1000;
  private final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 10000;
  private final int MAX_CONCURRENT_NEXUS_EXECUTION_SIZE = 10000;
  private final CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
  private final CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_NEXUS_EXECUTION_SIZE);
  private final TestStatsReporter reporter = new TestStatsReporter();
  private static final MaybeFailWFTResponseInterceptor MAYBE_FAIL_INTERCEPTOR =
      new MaybeFailWFTResponseInterceptor();
  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .setGrpcClientInterceptors(Collections.singletonList(MAYBE_FAIL_INTERCEPTOR))
                  .build())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      new CompositeTuner(
                          workflowTaskSlotSupplier,
                          activityTaskSlotSupplier,
                          localActivitySlotSupplier,
                          nexusSlotSupplier))
                  .build())
          .setMetricsScope(metricsScope)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .setWorkflowTypes(UnblockableWorkflow.class)
          .setDoNotStart(true)
          .build();

  @Before
  public void setup() {
    reporter.flush();
    MAYBE_FAIL_INTERCEPTOR.reset();
  }

  @After
  public void tearDown() {
    testWorkflowRule.getTestEnvironment().close();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
  }

  public static class UnblockableWorkflow implements TestWorkflows.TestSignaledWorkflow {
    private boolean unblocked = false;

    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                // If the task comes eagerly, since the interceptor blows us up we drop it
                // and that can cause timeouts.
                .setDisableEagerExecution(true)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String execute() {
      Workflow.await(() -> unblocked);
      activities.activity();
      return "";
    }

    @Override
    public void signal(String arg) {
      unblocked = true;
    }
  }

  @Test
  public void TestWFTResponseFailsThenWorks() {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflows.TestSignaledWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflows.TestSignaledWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                // If the task comes eagerly, since the interceptor blows us up we drop it
                // and that can cause timeouts.
                .setDisableEagerExecution(true)
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::execute);
    workflow.signal("whatever");
    workflow.execute();
    // All slots should be available
    assertAllSlotsAvailable();
  }

  @Test
  public void TestActivityResponseFailsThenWorks() {
    MAYBE_FAIL_INTERCEPTOR.failActivity = true;
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflows.TestSignaledWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflows.TestSignaledWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::execute);
    workflow.signal("whatever");
    workflow.execute();
    // All slots should be available
    assertAllSlotsAvailable();
  }

  private void assertAllSlotsAvailable() {
    try {
      // There can be a delay in metrics emission, another option if this
      // is too flaky is to poll the metrics.
      Thread.sleep(100);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("WorkflowWorker"),
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("ActivityWorker"),
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("LocalActivityWorker"),
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
    reporter.assertGauge(MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("WorkflowWorker"), 0);
    reporter.assertGauge(MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("ActivityWorker"), 0);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("LocalActivityWorker"), 0);
  }

  private Map<String, String> getWorkerTags(String workerType) {
    return ImmutableMap.of(
        "worker_type",
        workerType,
        "task_queue",
        testWorkflowRule.getTaskQueue(),
        "namespace",
        "UnitTest");
  }

  private static class MaybeFailWFTResponseInterceptor implements ClientInterceptor {
    boolean didFailOnce = false;
    boolean failActivity;

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      if (!failActivity && method == WorkflowServiceGrpc.getRespondWorkflowTaskCompletedMethod()) {
        return new MaybeFailWFTResponseInterceptor.FailResponseCall<>(
            next.newCall(method, callOptions));
      } else if (failActivity
          && method == WorkflowServiceGrpc.getRespondActivityTaskCompletedMethod()) {
        return new MaybeFailWFTResponseInterceptor.FailResponseCall<>(
            next.newCall(method, callOptions));
      }
      return next.newCall(method, callOptions);
    }

    public void reset() {
      didFailOnce = false;
      failActivity = false;
    }

    private final class FailResponseCall<ReqT, RespT>
        extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

      FailResponseCall(ClientCall<ReqT, RespT> call) {
        super(call);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {

        responseListener =
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                responseListener) {
              @Override
              public void onMessage(RespT message) {
                if (!didFailOnce) {
                  didFailOnce = true;
                  // Throw some non-retryable error code
                  throw new StatusRuntimeException(Status.UNAUTHENTICATED);
                }
                super.onMessage(message);
              }
            };
        super.start(responseListener, headers);
      }
    }
  }
}
