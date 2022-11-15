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

package io.temporal.workflow.deadlockdetector;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.common.env.DebugModeUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowLongArg;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(value = Parameterized.class)
public class DeadlockDetectorTest {

  private final boolean debugMode;

  public DeadlockDetectorTest(boolean debugMode) {
    this.debugMode = debugMode;
  }

  private final WorkflowImplementationOptions workflowImplementationOptions =
      WorkflowImplementationOptions.newBuilder()
          .setFailWorkflowExceptionTypes(Throwable.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(workflowImplementationOptions, TestDeadlockWorkflow.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithDDDTimeout =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(workflowImplementationOptions, TestDeadlockWorkflow.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(500).build())
          .build();

  @Before
  public void setUp() throws Exception {
    DebugModeUtils.override(debugMode);
  }

  @After
  public void tearDown() throws Exception {
    DebugModeUtils.reset();
  }

  @Test
  public void testDefaultDeadlockDetector() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    TestWorkflowLongArg workflow =
        workflowClient.newWorkflowStub(
            TestWorkflowLongArg.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(20))
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .build());
    try {
      workflow.execute(2000);
      if (!DebugModeUtils.isTemporalDebugModeOn()) {
        fail("not reachable in non-debug mode");
      }
    } catch (WorkflowFailedException e) {
      if (DebugModeUtils.isTemporalDebugModeOn()) {
        fail("not reachable in debug mode");
      }
      Throwable failure = e;
      while (failure.getCause() != null) {
        failure = failure.getCause();
      }
      assertTrue(failure.getMessage().contains("Potential deadlock detected"));
      assertTrue(failure.getMessage().contains("Workflow.await"));
    }
  }

  @Test
  public void testSetDeadlockDetector() {
    WorkflowClient workflowClient = testWorkflowRuleWithDDDTimeout.getWorkflowClient();
    TestWorkflowLongArg workflow =
        workflowClient.newWorkflowStub(
            TestWorkflowLongArg.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(20))
                .setTaskQueue(testWorkflowRuleWithDDDTimeout.getTaskQueue())
                .build());
    try {
      workflow.execute(750);
      if (!DebugModeUtils.isTemporalDebugModeOn()) {
        fail("not reachable in non-debug mode");
      }
    } catch (WorkflowFailedException e) {
      if (DebugModeUtils.isTemporalDebugModeOn()) {
        fail("not reachable in debug mode");
      }
      Throwable failure = e;
      while (failure.getCause() != null) {
        failure = failure.getCause();
      }
      assertTrue(failure.getMessage().contains("Potential deadlock detected"));
      assertTrue(failure.getMessage().contains("Workflow.await"));
    }
  }

  public static class TestDeadlockWorkflow implements TestWorkflowLongArg {
    @Override
    public void execute(long millis) {
      Async.procedure(() -> Workflow.await(() -> false));
      Workflow.sleep(Duration.ofSeconds(1));
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Workflow.wrap(e);
      }
    }
  }

  @Parameterized.Parameters
  public static Collection<Boolean> debugModeParams() {
    return Arrays.asList(false, true);
  }
}
