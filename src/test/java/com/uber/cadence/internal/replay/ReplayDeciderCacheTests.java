/*
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

package com.uber.cadence.internal.replay;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.cadence.internal.testservice.TestWorkflowService;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.testUtils.HistoryUtils;
import com.uber.cadence.worker.WorkerOptions;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import java.util.Map;
import junit.framework.TestCase;
import org.junit.Test;

public class ReplayDeciderCacheTests {

  @Test
  public void whenHistoryIsFullNewReplayDeciderIsReturnedAndCached_InitiallyEmpty()
      throws Exception {
    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(10, NoopScope.getInstance());
    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithInitialHistory();

    String runId = decisionTask.getWorkflowExecution().getRunId();

    assertCacheIsEmpty(replayDeciderCache, runId);

    // Act
    Decider decider = replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);

    // Assert
    assertEquals(decider, replayDeciderCache.getUnchecked(runId));
  }

  @Test
  public void whenHistoryIsFullNewReplayDeciderIsReturned_InitiallyCached() throws Exception {
    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(10, NoopScope.getInstance());
    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithInitialHistory();

    String runId = decisionTask.getWorkflowExecution().getRunId();
    Decider decider = replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);
    assertEquals(decider, replayDeciderCache.getUnchecked(runId));

    // Act
    Decider decider2 = replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);

    // Assert
    assertEquals(decider2, replayDeciderCache.getUnchecked(runId));
    assertNotSame(decider2, decider);
  }

  @Test
  public void whenHistoryIsPartialCachedEntryIsReturned() throws Exception {
    // Arrange
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, "domain")
            .put(MetricsTag.TASK_LIST, "stickyTaskList")
            .build();
    StatsReporter reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofMillis(500)).tagged(tags);

    DeciderCache replayDeciderCache = new DeciderCache(10, scope);
    TestWorkflowService service = new TestWorkflowService();
    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithInitialHistory(
            "domain", "taskList", "workflowType", service);

    String runId = decisionTask.getWorkflowExecution().getRunId();
    Decider decider = replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);
    assertEquals(decider, replayDeciderCache.getUnchecked(runId));

    // Act
    PollForDecisionTaskResponse decisionTask2 =
        HistoryUtils.generateDecisionTaskWithPartialHistoryFromExistingTask(
            decisionTask, "domain", "stickyTaskList", service);
    Decider decider2 = replayDeciderCache.getOrCreate(decisionTask2, this::createFakeDecider);

    // Assert
    // Wait for reporter
    Thread.sleep(1000);
    verify(reporter, times(1)).reportCounter(MetricsType.STICKY_CACHE_HIT, tags, 2);
    assertEquals(decider2, replayDeciderCache.getUnchecked(runId));
    assertEquals(decider2, decider);
  }

  @Test
  public void whenHistoryIsPartialAndCacheIsEmptyThenCacheEvictedExceptionIsThrown()
      throws Exception {
    // Arrange
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, "domain")
            .put(MetricsTag.TASK_LIST, "stickyTaskList")
            .build();
    StatsReporter reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofMillis(10)).tagged(tags);
    DeciderCache replayDeciderCache = new DeciderCache(10, scope);

    // Act
    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithPartialHistory();

    try {
      replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);
    } catch (DeciderCache.EvictedException ex) {

      // Wait for reporter
      Thread.sleep(600);
      verify(reporter, times(1)).reportCounter(MetricsType.STICKY_CACHE_MISS, tags, 1);
      return;
    }

    fail(
        "Expected replayDeciderCache.getOrCreate to throw ReplayDeciderCache.EvictedException but no exception was thrown");
  }

  @Test
  public void evictAnyWillInvalidateAnEntryRandomlyFromTheCache() throws Exception {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, "domain")
            .put(MetricsTag.TASK_LIST, "stickyTaskList")
            .build();
    StatsReporter reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofMillis(100)).tagged(tags);

    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(50, scope);
    PollForDecisionTaskResponse decisionTask1 =
        HistoryUtils.generateDecisionTaskWithInitialHistory();
    PollForDecisionTaskResponse decisionTask2 =
        HistoryUtils.generateDecisionTaskWithInitialHistory();
    PollForDecisionTaskResponse decisionTask3 =
        HistoryUtils.generateDecisionTaskWithInitialHistory();

    // Act
    Decider decider1 = replayDeciderCache.getOrCreate(decisionTask1, this::createFakeDecider);
    Decider decider2 = replayDeciderCache.getOrCreate(decisionTask2, this::createFakeDecider);
    Decider decider3 = replayDeciderCache.getOrCreate(decisionTask3, this::createFakeDecider);

    assertEquals(3, replayDeciderCache.size());

    replayDeciderCache.evictAny(decisionTask3.workflowExecution.runId);

    // Assert
    assertEquals(2, replayDeciderCache.size());

    // Wait for reporter
    Thread.sleep(600);
    verify(reporter, atLeastOnce())
        .reportCounter(eq(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION), eq(tags), anyInt());
  }

  @Test
  public void evictAnyWillNotInvalidateItself() throws Exception {
    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(50, NoopScope.getInstance());
    PollForDecisionTaskResponse decisionTask1 =
        HistoryUtils.generateDecisionTaskWithInitialHistory();

    // Act
    Decider decider1 = replayDeciderCache.getOrCreate(decisionTask1, this::createFakeDecider);

    assertEquals(1, replayDeciderCache.size());

    replayDeciderCache.evictAny(decisionTask1.workflowExecution.runId);

    // Assert
    assertEquals(1, replayDeciderCache.size());
  }

  private void assertCacheIsEmpty(DeciderCache cache, String runId) throws Exception {
    DeciderCache.EvictedException ex = null;
    try {
      cache.getUnchecked(runId);
    } catch (DeciderCache.EvictedException e) {
      ex = e;
    }
    TestCase.assertNotNull(ex);
  }

  private ReplayDecider createFakeDecider(PollForDecisionTaskResponse response) {
    return new ReplayDecider(
        new TestWorkflowService(),
        "domain",
        new ReplayWorkflow() {
          @Override
          public void start(HistoryEvent event, DecisionContext context) throws Exception {}

          @Override
          public void handleSignal(String signalName, byte[] input, long eventId) {}

          @Override
          public boolean eventLoop() throws Throwable {
            return false;
          }

          @Override
          public byte[] getOutput() {
            return new byte[0];
          }

          @Override
          public void cancel(String reason) {}

          @Override
          public void close() {}

          @Override
          public long getNextWakeUpTime() {
            return 0;
          }

          @Override
          public byte[] query(WorkflowQuery query) {
            return new byte[0];
          }

          @Override
          public WorkflowExecutionException mapUnexpectedException(Exception failure) {
            return null;
          }
        },
        new DecisionsHelper(response),
        new WorkerOptions.Builder().build().getMetricsScope(),
        false);
  }
}
