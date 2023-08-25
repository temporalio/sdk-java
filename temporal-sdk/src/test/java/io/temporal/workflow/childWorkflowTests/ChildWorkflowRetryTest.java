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

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.AngryChildActivityImpl;
import io.temporal.workflow.shared.TestWorkflows.AngryChild;
import io.temporal.workflow.shared.TestWorkflows.ITestChild;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowRetryTest {

  private final AtomicReference<String> lastStartedWorkflowType = new AtomicReference<>();
  private final AngryChildActivityImpl angryChildActivity = new AngryChildActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(UnsupportedOperationException.class)
                  .build(),
              TestChildWorkflowRetryWorkflow.class,
              AngryChild.class)
          .setActivityImplementations(angryChildActivity)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(
                      new WorkflowClientInterceptorBase() {
                        @Override
                        public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
                            WorkflowClientCallsInterceptor next) {
                          return new WorkflowClientCallsInterceptorBase(next) {
                            @Override
                            public WorkflowStartOutput start(WorkflowStartInput input) {
                              lastStartedWorkflowType.set(input.getWorkflowType());
                              return super.start(input);
                            }

                            @Override
                            public WorkflowSignalWithStartOutput signalWithStart(
                                WorkflowSignalWithStartInput input) {
                              lastStartedWorkflowType.set(
                                  input.getWorkflowStartInput().getWorkflowType());
                              return super.signalWithStart(input);
                            }
                          };
                        }
                      })
                  .setNamespace(NAMESPACE)
                  .build())
          .build();

  @Test
  public void testChildWorkflowRetry() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    try {
      client.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.toString(), e.getCause() instanceof ChildWorkflowFailure);
      assertTrue(e.toString(), e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals("test", ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(
          "message='simulated failure', type='test', nonRetryable=false",
          e.getCause().getCause().getMessage());
    }
    assertEquals("TestWorkflow1", lastStartedWorkflowType.get());
    assertEquals(3, angryChildActivity.getInvocationCount());
    WorkflowExecution execution = WorkflowStub.fromTyped(client).getExecution();
    testWorkflowRule.regenerateHistoryForReplay(
        execution.getWorkflowId(), "testChildWorkflowRetryHistory");
  }

  /**
   * Tests that history that was created before server side retry was supported is backwards
   * compatible with the client that supports the server side retry.
   */
  @Test
  public void testChildWorkflowRetryReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testChildWorkflowRetryHistory.json", TestChildWorkflowRetryWorkflow.class);
  }

  public static class TestChildWorkflowRetryWorkflow implements TestWorkflow1 {

    public TestChildWorkflowRetryWorkflow() {}

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(500))
              .setWorkflowTaskTimeout(Duration.ofSeconds(2))
              .setTaskQueue(taskQueue)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .build())
              .build();
      ITestChild child = Workflow.newChildWorkflowStub(ITestChild.class, options);

      return child.execute(taskQueue, 0);
    }
  }
}
