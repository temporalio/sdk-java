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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testUtils.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowStartFailureTest {
  private static final String EXISTING_WORKFLOW_ID = "duplicate-id";
  private static final Signal CHILD_EXECUTED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ParentWorkflowSpawningChildWithASpecificIdImpl.class, TestChildWorkflowImpl.class)
          .build();

  @Before
  public void setUp() throws Exception {
    TestWorkflows.NoArgsWorkflow alreadyExistingWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(EXISTING_WORKFLOW_ID)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());
    WorkflowStub.fromTyped(alreadyExistingWorkflow).start();

    // wait for the workflow to start and set the signal to clear the signal for the future
    // assertion
    CHILD_EXECUTED.waitForSignal(5, TimeUnit.SECONDS);
    CHILD_EXECUTED.clearSignal();
  }

  @Test
  public void childWorkflowAlreadyExists() throws InterruptedException {
    ParentWorkflowSpawningChildWithASpecificId workflow =
        testWorkflowRule.newWorkflowStub(ParentWorkflowSpawningChildWithASpecificId.class);
    assertEquals("ok", workflow.execute());
    assertFalse(CHILD_EXECUTED.waitForSignal(1, TimeUnit.SECONDS));
  }

  @WorkflowInterface
  public interface ParentWorkflowSpawningChildWithASpecificId {
    @WorkflowMethod
    String execute();
  }

  public static class ParentWorkflowSpawningChildWithASpecificIdImpl
      implements ParentWorkflowSpawningChildWithASpecificId {
    @Override
    public String execute() {
      TestWorkflows.NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(
              TestWorkflows.NoArgsWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId(EXISTING_WORKFLOW_ID)
                  .validateAndBuildWithDefaults());
      Promise<Void> procedure = Async.procedure(child::execute);

      ChildWorkflowFailure childWorkflowFailure =
          assertThrows(
              ChildWorkflowFailure.class, () -> Workflow.getWorkflowExecution(child).get());
      Throwable cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof WorkflowExecutionAlreadyStarted);

      childWorkflowFailure = assertThrows(ChildWorkflowFailure.class, procedure::get);
      cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof WorkflowExecutionAlreadyStarted);

      return "ok";
    }
  }

  public static class TestChildWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      if (!WorkflowUnsafe.isReplaying()) {
        CHILD_EXECUTED.signal();
      }
      Workflow.sleep(Duration.ofHours(1));
    }
  }
}
