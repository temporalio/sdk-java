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

package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;

public class WaitingWorkflowQueryTest {

  private static final Signal QUERY_READY = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityAndQueryWorkflow.class)
          .build();

  @Test
  public void query() throws ExecutionException, InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(30))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowWithQuery workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class, options);
    WorkflowClient.start(workflowStub::execute);

    QUERY_READY.waitForSignal();
    assertEquals("started", workflowStub.query());
  }

  public static final class TestLocalActivityAndQueryWorkflow
      implements TestWorkflows.TestWorkflowWithQuery {

    private String queryResult = "";

    @Override
    public String execute() {
      queryResult = "started";
      QUERY_READY.signal();
      Workflow.sleep(30_000);
      return "done";
    }

    @Override
    public String query() {
      return queryResult;
    }
  }
}
