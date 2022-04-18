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

package io.temporal.internal.replay;

import static io.temporal.testUtils.HistoryUtils.HOST_TASK_QUEUE;
import static io.temporal.testUtils.HistoryUtils.NAMESPACE;
import static io.temporal.testUtils.HistoryUtils.WORKFLOW_TYPE;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Map;
import java.util.Optional;
import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ReplayWorkflowRunTaskHandlerCacheTests {

  private Scope metricsScope;
  private TestStatsReporter reporter;

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Before
  public void setUp() {
    reporter = new TestStatsReporter();
    metricsScope = new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofMillis(10));
  }

  @Test
  public void whenHistoryIsFullNewWorkflowExecutorIsReturnedAndCached_InitiallyEmpty()
      throws Exception {
    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, new NoopScope());
    PollWorkflowTaskQueueResponse workflowTask =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();

    String runId = workflowTask.getWorkflowExecution().getRunId();

    assertCacheIsEmpty(cache, runId);

    // Act
    WorkflowRunTaskHandler workflowRunTaskHandler =
        cache.getOrCreate(workflowTask, metricsScope, () -> createFakeExecutor(workflowTask));

    // Assert
    assertNotEquals(
        workflowRunTaskHandler,
        cache.getOrCreate(workflowTask, metricsScope, () -> createFakeExecutor(workflowTask)));
  }

  @Test
  public void whenHistoryIsFullNewWorkflowExecutorIsReturned_InitiallyCached() throws Exception {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, new NoopScope());
    PollWorkflowTaskQueueResponse workflowTask1 =
        HistoryUtils.generateWorkflowTaskWithInitialHistory(
            "namespace", "taskQueue", "workflowType", testWorkflowRule.getWorkflowServiceStubs());

    WorkflowRunTaskHandler workflowRunTaskHandler =
        cache.getOrCreate(workflowTask1, metricsScope, () -> createFakeExecutor(workflowTask1));
    cache.addToCache(workflowTask1.getWorkflowExecution(), workflowRunTaskHandler);

    PollWorkflowTaskQueueResponse workflowTask2 =
        HistoryUtils.generateWorkflowTaskWithPartialHistoryFromExistingTask(
            workflowTask1,
            "namespace",
            "stickyTaskQueue",
            testWorkflowRule.getWorkflowServiceStubs());

    assertEquals(
        workflowRunTaskHandler,
        cache.getOrCreate(
            workflowTask2, metricsScope, () -> doNotCreateFakeExecutor(workflowTask2)));

    // Act
    WorkflowRunTaskHandler workflowRunTaskHandler2 =
        cache.getOrCreate(workflowTask2, metricsScope, () -> createFakeExecutor(workflowTask2));

    // Assert
    assertEquals(
        workflowRunTaskHandler2,
        cache.getOrCreate(workflowTask2, metricsScope, () -> createFakeExecutor(workflowTask2)));
    assertSame(workflowRunTaskHandler2, workflowRunTaskHandler);
  }

  @Test(timeout = 2000)
  public void whenHistoryIsPartialCachedEntryIsReturned() throws Exception {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    // Arrange
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, "namespace")
            .put(MetricsTag.TASK_QUEUE, "stickyTaskQueue")
            .build();
    Scope scope = metricsScope.tagged(tags);

    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, scope);
    PollWorkflowTaskQueueResponse workflowTask =
        HistoryUtils.generateWorkflowTaskWithInitialHistory(
            "namespace", "taskQueue", "workflowType", testWorkflowRule.getWorkflowServiceStubs());

    WorkflowRunTaskHandler workflowRunTaskHandler =
        cache.getOrCreate(workflowTask, scope, () -> createFakeExecutor(workflowTask));
    cache.addToCache(workflowTask.getWorkflowExecution(), workflowRunTaskHandler);

    // Act
    PollWorkflowTaskQueueResponse workflowTask2 =
        HistoryUtils.generateWorkflowTaskWithPartialHistoryFromExistingTask(
            workflowTask,
            "namespace",
            "stickyTaskQueue",
            testWorkflowRule.getWorkflowServiceStubs());
    WorkflowRunTaskHandler workflowRunTaskHandler2 =
        cache.getOrCreate(workflowTask2, scope, () -> doNotCreateFakeExecutor(workflowTask2));

    // Assert
    // Wait for reporter
    Thread.sleep(100);
    reporter.assertCounter(MetricsType.STICKY_CACHE_HIT, tags, 1);

    assertEquals(workflowRunTaskHandler, workflowRunTaskHandler2);
  }

  @Test
  public void whenHistoryIsPartialAndCacheIsEmptyThenExceptionIsThrown() throws Exception {
    // Arrange
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, "namespace")
            .put(MetricsTag.TASK_QUEUE, "stickyTaskQueue")
            .build();
    Scope scope = metricsScope.tagged(tags);
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, scope);

    // Act
    PollWorkflowTaskQueueResponse workflowTask =
        HistoryUtils.generateWorkflowTaskWithPartialHistory();

    try {
      cache.getOrCreate(workflowTask, scope, () -> createFakeExecutor(workflowTask));
      fail(
          "Expected workflowExecutorCache.getOrCreate to throw IllegalArgumentException but no exception was thrown");
    } catch (IllegalArgumentException ex) {

      // Wait for reporter
      Thread.sleep(100);
      reporter.assertCounter(MetricsType.STICKY_CACHE_MISS, tags, 1);
    }
  }

  @Test
  public void evictAnyWillInvalidateAnEntryRandomlyFromTheCache() throws Exception {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, NAMESPACE)
            .put(MetricsTag.TASK_QUEUE, HOST_TASK_QUEUE)
            .put(MetricsTag.WORKFLOW_TYPE, WORKFLOW_TYPE)
            .build();
    Scope scope = metricsScope.tagged(tags);

    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(50, scope);
    PollWorkflowTaskQueueResponse workflowTask1 =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();
    PollWorkflowTaskQueueResponse workflowTask2 =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();
    PollWorkflowTaskQueueResponse workflowTask3 =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();

    // Act
    WorkflowRunTaskHandler workflowRunTaskHandler =
        cache.getOrCreate(workflowTask1, scope, () -> createFakeExecutor(workflowTask1));
    cache.addToCache(workflowTask1.getWorkflowExecution(), workflowRunTaskHandler);
    workflowRunTaskHandler =
        cache.getOrCreate(workflowTask2, scope, () -> createFakeExecutor(workflowTask2));
    cache.addToCache(workflowTask2.getWorkflowExecution(), workflowRunTaskHandler);
    workflowRunTaskHandler =
        cache.getOrCreate(workflowTask3, scope, () -> createFakeExecutor(workflowTask3));
    WorkflowExecution execution = workflowTask3.getWorkflowExecution();
    cache.addToCache(execution, workflowRunTaskHandler);

    assertEquals(3, cache.size());

    cache.evictAnyNotInProcessing(execution, scope);

    // Assert
    assertEquals(2, cache.size());

    // Wait for reporter
    Thread.sleep(100);
    reporter.assertCounter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION, tags, 3);
  }

  @Test
  public void evictAnyWillNotInvalidateItself() throws Exception {
    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(50, new NoopScope());
    PollWorkflowTaskQueueResponse workflowTask1 =
        HistoryUtils.generateWorkflowTaskWithInitialHistory();

    // Act
    WorkflowRunTaskHandler workflowRunTaskHandler =
        cache.getOrCreate(workflowTask1, metricsScope, () -> createFakeExecutor(workflowTask1));
    WorkflowExecution execution = workflowTask1.getWorkflowExecution();
    cache.addToCache(execution, workflowRunTaskHandler);

    assertEquals(1, cache.size());

    cache.evictAnyNotInProcessing(execution, metricsScope);

    // Assert
    assertEquals(1, cache.size());
  }

  private void assertCacheIsEmpty(WorkflowExecutorCache cache, String runId) throws Exception {
    Throwable ex = null;
    try {
      PollWorkflowTaskQueueResponse workflowTask =
          PollWorkflowTaskQueueResponse.newBuilder()
              .setWorkflowExecution(WorkflowExecution.newBuilder().setRunId(runId))
              .build();
      cache.getOrCreate(workflowTask, metricsScope, () -> doNotCreateFakeExecutor(workflowTask));
    } catch (AssertionError e) {
      ex = e;
    }
    TestCase.assertNotNull(ex);
  }

  private ReplayWorkflowRunTaskHandler doNotCreateFakeExecutor(
      @SuppressWarnings("unused") PollWorkflowTaskQueueResponse response) {
    fail("should not be called");
    return null;
  }

  private ReplayWorkflowRunTaskHandler createFakeExecutor(PollWorkflowTaskQueueResponse response) {
    return new ReplayWorkflowRunTaskHandler(
        null,
        "namespace",
        new ReplayWorkflow() {
          @Override
          public void start(HistoryEvent event, ReplayWorkflowContext context) {}

          @Override
          public void handleSignal(String signalName, Optional<Payloads> input, long eventId) {}

          @Override
          public boolean eventLoop() {
            return false;
          }

          @Override
          public Optional<Payloads> getOutput() {
            return Optional.empty();
          }

          @Override
          public void cancel(String reason) {}

          @Override
          public void close() {}

          @Override
          public Optional<Payloads> query(WorkflowQuery query) {
            return Optional.empty();
          }

          @Override
          public Failure mapExceptionToFailure(Throwable exception) {
            return null;
          }

          @Override
          public WorkflowImplementationOptions getWorkflowImplementationOptions() {
            return WorkflowImplementationOptions.newBuilder().build();
          }
        },
        response,
        SingleWorkerOptions.newBuilder().build(),
        metricsScope,
        (a, b) -> true);
  }
}
