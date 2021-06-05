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

package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClient;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class ExceptionInSignalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalExceptionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .setTestTimeoutSeconds(20)
          .build();

  @Test
  public void testExceptionInSignal() throws InterruptedException {
    TestSignaledWorkflow signalWorkflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestSignaledWorkflow.class);
    CompletableFuture<String> result = WorkflowClient.execute(signalWorkflow::execute);
    signalWorkflow.signal("test");
    try {
      result.get(1, TimeUnit.SECONDS);
      fail("not reachable");
    } catch (Exception e) {
      // exception expected here.
    }

    // Suspend polling so that workflow tasks are not retried. Otherwise it will affect our thread
    // count.
    testWorkflowRule.getTestEnvironment().getWorkerFactory().suspendPolling();

    // Wait for workflow task retry to finish.
    Thread.sleep(5000);

    int workflowThreads = 0;
    ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
    for (ThreadInfo thread : threads) {
      if (thread.getThreadName().startsWith("workflow")) {
        workflowThreads++;
      }
    }

    assertTrue(
        "workflow threads might leak, #workflowThreads = " + workflowThreads, workflowThreads < 20);
  }

  public static class TestSignalExceptionWorkflowImpl implements TestSignaledWorkflow {
    private final boolean signaled = false;

    @Override
    public String execute() {
      Workflow.await(() -> signaled);
      return null;
    }

    @Override
    public void signal(String arg) {
      for (int i = 0; i < 10; i++) {
        Async.procedure(() -> Workflow.sleep(Duration.ofHours(1)));
      }

      throw new RuntimeException("exception in signal method");
    }
  }
}
