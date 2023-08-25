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
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
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
            eagerActivityDispatcher);

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
    worker.shutdown(new ShutdownManager(), true).get();
    // Verify we only handled two tasks
    verify(taskHandler, times(2)).handleWorkflowTask(any());
  }
}
