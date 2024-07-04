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

import static java.nio.charset.StandardCharsets.UTF_8;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.replay.ReplayWorkflowTaskHandler;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.Test;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowWorkerTest {
  private static final Logger log = LoggerFactory.getLogger(WorkflowWorkerTest.class);
  private final TestStatsReporter reporter = new TestStatsReporter();
  private static final String WORKFLOW_ID = "test-workflow-id";
  private static final String RUN_ID = "test-run-id";
  private static final String WORKFLOW_TYPE = "test-workflow-type";

  @Test
  public void concurrentPollRequestLockTest() throws Exception {
    // Test that if the server sends multiple concurrent workflow tasks for the same workflow the
    // SDK holds the lock during all processing.
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier =
        new TrackingSlotSupplier<>(new FixedSizeSlotSupplier<>(100));
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);

    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);

    EagerActivityDispatcher eagerActivityDispatcher = mock(EagerActivityDispatcher.class);
    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setPollerOptions(PollerOptions.newBuilder().setPollThreadCount(3).build())
                .setMetricsScope(metricsScope)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            eagerActivityDispatcher,
            slotSupplier);

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    CountDownLatch pollTaskQueueLatch = new CountDownLatch(1);
    CountDownLatch blockPollTaskQueueLatch = new CountDownLatch(1);

    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse)
        .thenReturn(pollResponse)
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  pollTaskQueueLatch.countDown();
                  return pollResponse;
                })
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  blockPollTaskQueueLatch.await();
                  return null;
                });

    CountDownLatch handleTaskLatch = new CountDownLatch(1);
    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
        .thenAnswer(
            (Answer<WorkflowTaskHandler.Result>)
                invocation -> {
                  // Slightly larger than the lock timeout hard coded in WorkflowWorker
                  handleTaskLatch.countDown();
                  Thread.sleep(6000);

                  return new WorkflowTaskHandler.Result(
                      WORKFLOW_TYPE,
                      RespondWorkflowTaskCompletedRequest.newBuilder().build(),
                      null,
                      null,
                      null,
                      false,
                      (id) -> {
                        // verify the lock is still being held
                        assertEquals(runLockManager.totalLocks(), 1);
                      });
                });

    // Mock the server responding to a workflow task complete with another workflow task
    CountDownLatch respondTaskLatch = new CountDownLatch(1);
    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenAnswer(
            (Answer<RespondWorkflowTaskCompletedResponse>)
                invocation -> {
                  // verify the lock is still being held
                  assertEquals(runLockManager.totalLocks(), 1);
                  return RespondWorkflowTaskCompletedResponse.newBuilder()
                      .setWorkflowTask(pollResponse)
                      .build();
                })
        .thenAnswer(
            (Answer<RespondWorkflowTaskCompletedResponse>)
                invocation -> {
                  // verify the lock is still being held
                  assertEquals(runLockManager.totalLocks(), 1);
                  respondTaskLatch.countDown();
                  return RespondWorkflowTaskCompletedResponse.newBuilder().build();
                });

    assertTrue(worker.start());
    // All slots should be available
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        ImmutableMap.of("worker_type", "WorkflowWorker"),
        100.0);
    // Wait until we have got all the polls
    pollTaskQueueLatch.await();
    // Wait until the worker handles at least one WFT
    handleTaskLatch.await();
    // Sleep to allow metrics to be published
    Thread.sleep(100);
    // Since all polls have the same runID only one should get through, the other two should be
    // blocked
    assertEquals(runLockManager.totalLocks(), 1);
    // Verify 3 slots have been used
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        ImmutableMap.of("worker_type", "WorkflowWorker"),
        97.0);
    // Wait for the worker to respond, by this time the other blocked tasks should have timed out
    respondTaskLatch.await();
    // Sleep to allow metrics to be published
    Thread.sleep(100);
    // No task should have the lock anymore
    assertEquals(runLockManager.totalLocks(), 0);
    // All slots should be available
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        ImmutableMap.of("worker_type", "WorkflowWorker"),
        100.0);
    // Cleanup
    worker.shutdown(new ShutdownManager(), false).get();
    // Verify we only handled two tasks
    verify(taskHandler, times(2)).handleWorkflowTask(any());
  }

  @Test
  public void respondWorkflowTaskFailureMetricTest() throws Exception {
    // Test that if the SDK gets a failure on RespondWorkflowTaskCompleted it does not increment
    // workflow_task_execution_failed.
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(1));
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, runLockManager, metricsScope);
    TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier =
        new TrackingSlotSupplier<>(new FixedSizeSlotSupplier<>(10));

    WorkflowTaskHandler taskHandler = mock(WorkflowTaskHandler.class);
    when(taskHandler.isAnyTypeSupported()).thenReturn(true);

    EagerActivityDispatcher eagerActivityDispatcher = mock(EagerActivityDispatcher.class);
    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "task_queue",
            "sticky_task_queue",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setPollerOptions(PollerOptions.newBuilder().setPollThreadCount(1).build())
                .setMetricsScope(metricsScope)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            eagerActivityDispatcher,
            slotSupplier);

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    CountDownLatch pollTaskQueueLatch = new CountDownLatch(1);
    CountDownLatch blockPollTaskQueueLatch = new CountDownLatch(1);

    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse)
        .thenAnswer(
            (Answer<PollWorkflowTaskQueueResponse>)
                invocation -> {
                  pollTaskQueueLatch.countDown();
                  blockPollTaskQueueLatch.await();
                  return null;
                });
    ;

    CountDownLatch handleTaskLatch = new CountDownLatch(1);

    when(taskHandler.handleWorkflowTask(any(PollWorkflowTaskQueueResponse.class)))
        .thenAnswer(
            (Answer<WorkflowTaskHandler.Result>)
                invocation -> {
                  handleTaskLatch.countDown();

                  return new WorkflowTaskHandler.Result(
                      WORKFLOW_TYPE,
                      RespondWorkflowTaskCompletedRequest.newBuilder().build(),
                      null,
                      null,
                      null,
                      false,
                      null);
                });

    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenThrow(new RuntimeException());

    assertTrue(worker.start());
    // Wait until we have got all the polls
    pollTaskQueueLatch.await();
    // Wait until the worker handles at least one WFT
    handleTaskLatch.await();
    // Cleanup
    worker.shutdown(new ShutdownManager(), false).get();
    // Make sure we don't report workflow task failure
    reporter.assertNoMetric(
        MetricsType.WORKFLOW_TASK_EXECUTION_FAILURE_COUNTER,
        ImmutableMap.of("worker_type", "WorkflowWorker", "workflow_type", "test-workflow-type"));
  }

  @Test
  public void resetWorkflowIdFromWorkflowTaskTest() throws Throwable {
    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());

    WorkflowRunLockManager runLockManager = new WorkflowRunLockManager();

    Scope metricScope = new NoopScope();
    WorkflowExecutorCache cache = new WorkflowExecutorCache(1, runLockManager, metricScope);

    TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier =
        new TrackingSlotSupplier<>(new FixedSizeSlotSupplier<>(1));

    WorkflowTaskHandler rootTaskHandler =
        new ReplayWorkflowTaskHandler(
            "namespace",
            setUpMockWorkflowFactory(),
            cache,
            SingleWorkerOptions.newBuilder().build(),
            InternalUtils.createStickyTaskQueue("sticky", "taskQueue"),
            Duration.ofSeconds(5),
            client,
            null);
    // Queue to pass the reset event id from WorkflowTaskHandler to the test
    BlockingQueue<Long> resetEventIdQueue = new ArrayBlockingQueue<>(1);
    // Wrap the root task handler to capture the reset event id
    WorkflowTaskHandler taskHandler =
        new WorkflowTaskHandler() {
          @Override
          public WorkflowTaskHandler.Result handleWorkflowTask(PollWorkflowTaskQueueResponse task)
              throws Exception {
            WorkflowTaskHandler.Result result = rootTaskHandler.handleWorkflowTask(task);
            return new WorkflowTaskHandler.Result(
                result.getWorkflowType(),
                result.getTaskCompleted(),
                result.getTaskFailed(),
                result.getQueryCompleted(),
                result.getRequestRetryOptions(),
                result.isCompletionCommand(),
                (id) -> {
                  resetEventIdQueue.add(id);
                  result.getResetEventIdHandle().apply(id);
                });
          }

          @Override
          public boolean isAnyTypeSupported() {
            return rootTaskHandler.isAnyTypeSupported();
          }
        };

    EagerActivityDispatcher eagerActivityDispatcher = mock(EagerActivityDispatcher.class);
    WorkflowWorker worker =
        new WorkflowWorker(
            client,
            "default",
            "taskQueue",
            "sticky",
            SingleWorkerOptions.newBuilder()
                .setIdentity("test_identity")
                .setBuildId(UUID.randomUUID().toString())
                .setPollerOptions(PollerOptions.newBuilder().setPollThreadCount(1).build())
                .setMetricsScope(metricScope)
                .build(),
            runLockManager,
            cache,
            taskHandler,
            eagerActivityDispatcher,
            slotSupplier);

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(client.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);

    PollWorkflowTaskQueueResponse pollResponse =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setHistory(HistoryUtils.generateWorkflowTaskWithInitialHistory().getHistory())
            .setTaskToken(ByteString.copyFrom("token", UTF_8))
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId(RUN_ID).build())
            .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
            .build();

    when(blockingStub.pollWorkflowTaskQueue(any(PollWorkflowTaskQueueRequest.class)))
        .thenReturn(pollResponse);
    RespondWorkflowTaskCompletedResponse workflowTaskResponse =
        RespondWorkflowTaskCompletedResponse.newBuilder().setResetHistoryEventId(1).build();
    when(blockingStub.respondWorkflowTaskCompleted(any(RespondWorkflowTaskCompletedRequest.class)))
        .thenReturn(workflowTaskResponse);

    assertTrue(worker.start());
    // Assert that the reset event id is received by WorkflowTaskHandler
    assertEquals(Long.valueOf(1), resetEventIdQueue.take());
    // Cleanup
    worker.shutdown(new ShutdownManager(), true).get();
  }

  private ReplayWorkflowFactory setUpMockWorkflowFactory() throws Throwable {
    ReplayWorkflow mockWorkflow = mock(ReplayWorkflow.class);
    ReplayWorkflowFactory mockFactory = mock(ReplayWorkflowFactory.class);

    when(mockFactory.getWorkflow(any(), any())).thenReturn(mockWorkflow);
    when(mockFactory.isAnyTypeSupported()).thenReturn(true);
    when(mockWorkflow.eventLoop()).thenReturn(false);
    return mockFactory;
  }
}
