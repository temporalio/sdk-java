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

package io.temporal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class ListWorkflowExecutionsTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflows.DoNothingNoArgsWorkflow.class)
          .build();

  @Test
  public void listWorkflowExecutions_returnsAllExecutions() throws InterruptedException {
    final int EXECUTIONS_COUNT = 30;
    final String QUERY = "TaskQueue='" + testWorkflowRule.getTaskQueue() + "'";

    assumeTrue(
        "Test Server doesn't support listWorkflowExecutions endpoint yet",
        SDKTestWorkflowRule.useExternalService);

    for (int i = 0; i < EXECUTIONS_COUNT; i++) {
      WorkflowStub.fromTyped(testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class))
          .start();
    }

    // listWorkflowExecutions is Visibility API
    // Temporal Visibility has latency and is not transactional with the Server API call
    Thread.sleep(4_000);

    List<WorkflowExecutionMetadata> executions =
        testWorkflowRule.getWorkflowClient().listExecutions(QUERY).collect(Collectors.toList());
    assertEquals(
        "Should return the original amount of the workflows", EXECUTIONS_COUNT, executions.size());
    Set<String> workflowIds =
        executions.stream()
            .map(meta -> meta.getExecution().getWorkflowId())
            .collect(Collectors.toSet());
    assertEquals(
        "Each of the returned workflowIds should be different",
        EXECUTIONS_COUNT,
        workflowIds.size());
  }

  @Test
  public void listWorkflowExecutions_returnsAllExecutions_pagination() throws InterruptedException {
    final int EXECUTIONS_COUNT = 30;
    final String QUERY = "TaskQueue='" + testWorkflowRule.getTaskQueue() + "'";

    assumeTrue(
        "Test Server doesn't support listWorkflowExecutions endpoint yet",
        SDKTestWorkflowRule.useExternalService);

    for (int i = 0; i < EXECUTIONS_COUNT; i++) {
      WorkflowStub.fromTyped(testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class))
          .start();
    }

    // listWorkflowExecutions is Visibility API
    // Temporal Visibility has latency and is not transactional with the Server API call
    Thread.sleep(4_000);

    WorkflowClientInternalImpl workflowClientInternalImpl =
        new WorkflowClientInternalImpl(
            testWorkflowRule.getWorkflowServiceStubs(),
            testWorkflowRule.getWorkflowClient().getOptions());

    List<WorkflowExecutionMetadata> executions =
        workflowClientInternalImpl.listExecutions(QUERY, 5).collect(Collectors.toList());
    assertEquals(
        "Should return the original amount of the workflows", EXECUTIONS_COUNT, executions.size());
    Set<String> workflowIds =
        executions.stream()
            .map(meta -> meta.getExecution().getWorkflowId())
            .collect(Collectors.toSet());

    assertEquals(
        "Each of the returned workflowIds should be different",
        EXECUTIONS_COUNT,
        workflowIds.size());
  }
}
