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

package io.temporal.client.functional;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * These tests are covering the situation when a getResult wait time crosses a boundary of a long
 * poll timeout.
 */
public class GetResultsOverLongPollTimeoutTest {
  private static final int LONG_POLL_TIMEOUT_SECONDS = 5;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(TestWorkflowImpl.class)
          .build();

  private WorkflowServiceStubs clientStubs;
  private WorkflowClient workflowClient;

  @Before
  public void setUp() {
    testWorkflowRule.getWorkflowClient().getWorkflowServiceStubs();
    WorkflowServiceStubsOptions options =
        testWorkflowRule.getWorkflowClient().getWorkflowServiceStubs().getOptions();
    WorkflowServiceStubsOptions modifiedOptions =
        WorkflowServiceStubsOptions.newBuilder(options)
            .setRpcLongPollTimeout(Duration.ofSeconds(LONG_POLL_TIMEOUT_SECONDS))
            .build();

    this.clientStubs = WorkflowServiceStubs.newServiceStubs(modifiedOptions);
    this.workflowClient =
        WorkflowClient.newInstance(clientStubs, testWorkflowRule.getWorkflowClient().getOptions());
  }

  @After
  public void tearDown() {
    clientStubs.shutdown();
  }

  @Test(timeout = 2 * LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResults() {
    TestWorkflows.NoArgsWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestWorkflows.NoArgsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::execute);
    WorkflowStub.fromTyped(workflow).getResult(Void.class);
  }

  @Test(timeout = 2 * LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResultAsync() throws ExecutionException, InterruptedException {
    TestWorkflows.NoArgsWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestWorkflows.NoArgsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::execute);
    WorkflowStub.fromTyped(workflow).getResultAsync(Void.class).get();
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofSeconds(3 * LONG_POLL_TIMEOUT_SECONDS / 2));
    }
  }
}
