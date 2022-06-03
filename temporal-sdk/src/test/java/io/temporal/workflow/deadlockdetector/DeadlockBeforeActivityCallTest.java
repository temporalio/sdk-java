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

import static org.junit.Assert.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.common.DebugModeUtils;
import io.temporal.testUtils.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DeadlockBeforeActivityCallTest {
  private final WorkflowImplementationOptions workflowImplementationOptions =
      WorkflowImplementationOptions.newBuilder()
          .setFailWorkflowExceptionTypes(Throwable.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              workflowImplementationOptions, TestDeadlockWorkflowWithActivityAfterDeadlock.class)
          .setActivityImplementations(new TestActivities.TestActivityImpl())
          .build();

  private static final AtomicReference<Exception> EXCEPTION_CAUGHT_IN_WORKFLOW_CODE =
      new AtomicReference<>();
  private static final Signal WORKFLOW_CODE_COMPLETED = new Signal();

  @Before
  public void setUp() throws Exception {
    DebugModeUtils.override(false);
  }

  @After
  public void tearDown() throws Exception {
    DebugModeUtils.reset();
  }

  @Test
  public void testDeadlockDetectedBeforeAnActivityCall() throws InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    TestWorkflows.NoArgsWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestWorkflows.NoArgsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(20))
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .build());

    Throwable failure = assertThrows(WorkflowFailedException.class, workflow::execute);
    while (failure.getCause() != null) {
      failure = failure.getCause();
    }
    assertTrue(failure.getMessage().contains("Potential deadlock detected"));

    WORKFLOW_CODE_COMPLETED.waitForSignal();
    assertNull(EXCEPTION_CAUGHT_IN_WORKFLOW_CODE.get());
  }

  public static class TestDeadlockWorkflowWithActivityAfterDeadlock
      implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      try {
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw Workflow.wrap(e);
        }
        try {
          TestActivities.NoArgsActivity activity =
              Workflow.newActivityStub(
                  TestActivities.NoArgsActivity.class,
                  SDKTestOptions.newActivityOptions20sScheduleToClose());
          activity.execute();
        } catch (Exception e) {
          EXCEPTION_CAUGHT_IN_WORKFLOW_CODE.set(e);
        }
      } finally {
        WORKFLOW_CODE_COMPLETED.signal();
      }
    }
  }
}
