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

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.TimeoutFailure;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.DeterminismFailingWorkflowImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NonDeterministicWorkflowPolicyBlockWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(DeterminismFailingWorkflowImpl.class)
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
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      // expected to timeout as workflow is going get blocked.
      Assert.assertTrue(e.getCause() instanceof TimeoutFailure);
    }

    int workflowRootThreads = 0;
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
    for (ThreadInfo thread : threads) {
      if (thread.getThreadName().contains("workflow-root")) {
        workflowRootThreads++;
      }
    }

    Assert.assertTrue("workflow threads might leak", workflowRootThreads < 10);
  }
}
