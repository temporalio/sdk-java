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

import static io.temporal.testUtils.HistoryUtils.*;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testUtils.HistoryUtils;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;

public class ReplayWorkflowExecutorCacheTests {

  private Scope metricsScope;
  private TestStatsReporter reporter;

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
    WorkflowExecutor workflowExecutor =
        cache.getOrCreate(workflowTask, metricsScope, () -> createFakeExecutor(workflowTask));

    // Assert
    assertNotEquals(
        workflowExecutor,
        cache.getOrCreate(workflowTask, metricsScope, () -> createFakeExecutor(workflowTask)));
  }

  @Test
  public void whenHistoryIsFullNewWorkflowExecutorIsReturned_InitiallyCached() throws Exception {
    TestWorkflowService testService = new TestWorkflowService();
    WorkflowServiceStubs service = testService.newClientStub();

    // Arrange
    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, new NoopScope());
    PollWorkflowTaskQueueResponse workflowTask1 =
        HistoryUtils.generateWorkflowTaskWithInitialHistory(
            "namespace", "taskQueue", "workflowType", service);

    WorkflowExecutor workflowExecutor =
        cache.getOrCreate(workflowTask1, metricsScope, () -> createFakeExecutor(workflowTask1));
    cache.addToCache(workflowTask1.getWorkflowExecution().getRunId(), workflowExecutor);

    PollWorkflowTaskQueueResponse workflowTask2 =
        HistoryUtils.generateWorkflowTaskWithPartialHistoryFromExistingTask(
            workflowTask1, "namespace", "stickyTaskQueue", service);

    assertEquals(
        workflowExecutor,
        cache.getOrCreate(
            workflowTask2, metricsScope, () -> doNotCreateFakeExecutor(workflowTask2)));

    // Act
    WorkflowExecutor workflowExecutor2 =
        cache.getOrCreate(workflowTask2, metricsScope, () -> createFakeExecutor(workflowTask2));

    // Assert
    assertEquals(
        workflowExecutor2,
        cache.getOrCreate(workflowTask2, metricsScope, () -> createFakeExecutor(workflowTask2)));
    assertSame(workflowExecutor2, workflowExecutor);
    service.shutdownNow();
    try {
      service.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  @Test(timeout = 2000)
  public void whenHistoryIsPartialCachedEntryIsReturned() throws Exception {
    // Arrange
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.NAMESPACE, "namespace")
            .put(MetricsTag.TASK_QUEUE, "stickyTaskQueue")
            .build();
    Scope scope = metricsScope.tagged(tags);

    WorkflowExecutorCache cache = new WorkflowExecutorCache(10, scope);
    TestWorkflowService testService = new TestWorkflowService(true);
    WorkflowServiceStubs service = testService.newClientStub();
    try {
      PollWorkflowTaskQueueResponse workflowTask =
          HistoryUtils.generateWorkflowTaskWithInitialHistory(
              "namespace", "taskQueue", "workflowType", service);

      WorkflowExecutor workflowExecutor =
          cache.getOrCreate(workflowTask, scope, () -> createFakeExecutor(workflowTask));
      cache.addToCache(workflowTask.getWorkflowExecution().getRunId(), workflowExecutor);

      // Act
      PollWorkflowTaskQueueResponse workflowTask2 =
          HistoryUtils.generateWorkflowTaskWithPartialHistoryFromExistingTask(
              workflowTask, "namespace", "stickyTaskQueue", service);
      WorkflowExecutor workflowExecutor2 =
          cache.getOrCreate(workflowTask2, scope, () -> doNotCreateFakeExecutor(workflowTask2));

      // Assert
      // Wait for reporter
      Thread.sleep(100);
      reporter.assertCounter(MetricsType.STICKY_CACHE_HIT, tags, 1);

      assertEquals(workflowExecutor, workflowExecutor2);
    } finally {
      service.shutdownNow();
      service.awaitTermination(1, TimeUnit.SECONDS);
      testService.close();
    }
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
    WorkflowExecutor workflowExecutor =
        cache.getOrCreate(workflowTask1, scope, () -> createFakeExecutor(workflowTask1));
    cache.addToCache(workflowTask1.getWorkflowExecution().getRunId(), workflowExecutor);
    workflowExecutor =
        cache.getOrCreate(workflowTask2, scope, () -> createFakeExecutor(workflowTask2));
    cache.addToCache(workflowTask2.getWorkflowExecution().getRunId(), workflowExecutor);
    workflowExecutor =
        cache.getOrCreate(workflowTask3, scope, () -> createFakeExecutor(workflowTask3));
    cache.addToCache(workflowTask3.getWorkflowExecution().getRunId(), workflowExecutor);

    assertEquals(3, cache.size());

    cache.evictAnyNotInProcessing(workflowTask3.getWorkflowExecution().getRunId(), scope);

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
    WorkflowExecutor workflowExecutor =
        cache.getOrCreate(workflowTask1, metricsScope, () -> createFakeExecutor(workflowTask1));
    cache.addToCache(workflowTask1.getWorkflowExecution().getRunId(), workflowExecutor);

    assertEquals(1, cache.size());

    cache.evictAnyNotInProcessing(workflowTask1.getWorkflowExecution().getRunId(), metricsScope);

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

  private ReplayWorkflowExecutor doNotCreateFakeExecutor(
      @SuppressWarnings("unused") PollWorkflowTaskQueueResponse response) {
    fail("should not be called");
    return null;
  }

  private ReplayWorkflowExecutor createFakeExecutor(PollWorkflowTaskQueueResponse response) {
    return new ReplayWorkflowExecutor(
        null,
        "namespace",
        new ReplayWorkflow() {
          @Override
          public void start(HistoryEvent event, ReplayWorkflowContext context) {}

          @Override
          public void handleSignal(String signalName, Optional<Payloads> input, long eventId) {}

          @Override
          public boolean eventLoop() throws Throwable {
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
          public WorkflowExecutionException mapUnexpectedException(Throwable failure) {
            return null;
          }

          @Override
          public WorkflowExecutionException mapError(Throwable failure) {
            return null;
          }

          @Override
          public WorkflowImplementationOptions getWorkflowImplementationOptions() {
            return WorkflowImplementationOptions.newBuilder().build();
          }
        },
        response.toBuilder(),
        SingleWorkerOptions.newBuilder().build(),
        metricsScope,
        (a, b) -> true);
  }
}
