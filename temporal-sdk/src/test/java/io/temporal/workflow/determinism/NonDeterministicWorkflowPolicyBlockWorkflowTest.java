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

package io.temporal.workflow.determinism;

import static org.junit.Assert.*;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.worker.workflow.WorkflowMethodThreadNameStrategy;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.NonDeterministicException;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class NonDeterministicWorkflowPolicyBlockWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(DeterminismFailingWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testNonDeterministicWorkflowPolicyBlockWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflowStringArg workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflowStringArg.class, options);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      // expected to timeout as workflow is going get blocked.
      assertTrue(e.getCause() instanceof TimeoutFailure);
    }

    // these should be only first one failed WFT with non-deterministic error,
    // other WFTs after it should end with WORKFLOW_TASK_TIMED_OUT
    HistoryEvent nonDeterministicExceptionHistoryEvent =
        testWorkflowRule.getHistoryEvent(
            WorkflowStub.fromTyped(workflowStub).getExecution(),
            EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    WorkflowTaskFailedEventAttributes failedWFTEventAttributes =
        nonDeterministicExceptionHistoryEvent.getWorkflowTaskFailedEventAttributes();
    assertEquals(
        "A correct explicit non deterministic cause should be reported",
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR,
        failedWFTEventAttributes.getCause());
    assertEquals(
        NonDeterministicException.class.getName(),
        failedWFTEventAttributes.getFailure().getApplicationFailureInfo().getType());
  }

  @Test
  public void noThreadLeaks() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(7))
            .setWorkflowTaskTimeout(Duration.ofMillis(100))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflowStringArg workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflowStringArg.class, options);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      // expected to timeout as workflow is going get blocked.
      assertTrue(e.getCause() instanceof TimeoutFailure);
    }

    int workflowMethodThreads = 0;
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
    for (ThreadInfo thread : threads) {
      if (thread
          .getThreadName()
          .contains(WorkflowMethodThreadNameStrategy.WORKFLOW_MAIN_THREAD_PREFIX)) {
        workflowMethodThreads++;
      }
    }

    assertTrue("workflow threads might leak", workflowMethodThreads < 3);
  }
}
