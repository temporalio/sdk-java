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
import static junit.framework.TestCase.assertNotSame;
import static junit.framework.TestCase.fail;

import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.internal.testservice.TestWorkflowService;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.testUtils.HistoryUtils;
import com.uber.cadence.worker.WorkerOptions;
import junit.framework.TestCase;
import org.junit.Test;

public class ReplayDeciderCacheTests {

  @Test
  public void whenHistoryIsFullNewReplayDeciderIsReturnedAndCached_InitiallyEmpty()
      throws Exception {
    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(10);
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
    DeciderCache replayDeciderCache = new DeciderCache(10);
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
    DeciderCache replayDeciderCache = new DeciderCache(10);
    TestWorkflowService service = new TestWorkflowService();
    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithInitialHistory(
            "domain", "taskList", "workflowType", service);

    String runId = decisionTask.getWorkflowExecution().getRunId();
    Decider decider = replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);
    assertEquals(decider, replayDeciderCache.getUnchecked(runId));

    // Act
    decisionTask =
        HistoryUtils.generateDecisionTaskWithPartialHistoryFromExistingTask(
            decisionTask, "domain", "stickyTaskList", service);
    Decider decider2 = replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);

    // Assert
    assertEquals(decider2, replayDeciderCache.getUnchecked(runId));
    assertEquals(decider2, decider);
  }

  @Test
  public void whenHistoryIsPartialAndCacheIsEmptyThenCacheEvictedExceptionIsThrown()
      throws Exception {
    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(10);

    // Act
    PollForDecisionTaskResponse decisionTask =
        HistoryUtils.generateDecisionTaskWithPartialHistory();

    try {
      replayDeciderCache.getOrCreate(decisionTask, this::createFakeDecider);
    } catch (DeciderCache.EvictedException ex) {
      return;
    }

    fail(
        "Expected replayDeciderCache.getOrCreate to throw ReplayDeciderCache.EvictedException but no exception was thrown");
  }

  @Test
  public void evictNextWillInvalidateTheNextEntryInLineToBeEvicted() throws Exception {
    // Arrange
    DeciderCache replayDeciderCache = new DeciderCache(10);
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

    replayDeciderCache.evictNext();

    // Assert
    assertEquals(2, replayDeciderCache.size());
    String runId1 = decisionTask1.workflowExecution.getRunId();
    assertCacheIsEmpty(replayDeciderCache, runId1);
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
