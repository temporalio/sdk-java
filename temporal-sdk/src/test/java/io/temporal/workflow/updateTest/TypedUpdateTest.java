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

package io.temporal.workflow.updateTest;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.TestMultiArgWorkflowUpdateFunctions;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TypedUpdateTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(
              TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflowImpl.class)
          .build();

  @Test
  public void testTypedStubSync() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    Assert.assertEquals("func", workflow.func());
    Assert.assertEquals("input", workflow.func1("input"));
    Assert.assertEquals("input2", workflow.func2("input", 2));
    Assert.assertEquals("input23", workflow.func3("input", 2, 3));
    Assert.assertEquals("input234", workflow.func4("input", 2, 3, 4));
    Assert.assertEquals("input2345", workflow.func5("input", 2, 3, 4, 5));
    Assert.assertEquals("input23456", workflow.func6("input", 2, 3, 4, 5, 6));

    workflow.proc();
    workflow.proc1("input");
    workflow.proc2("input", 2);
    workflow.proc3("input", 2, 3);
    workflow.proc4("input", 2, 3, 4);
    workflow.proc5("input", 2, 3, 4, 5);
    workflow.proc6("input", 2, 3, 4, 5, 6);

    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("procinputinput2input23input234input2345input23456", result);
  }

  @Test
  public void testTypedAsync() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    UpdateOptions<String> updateOptions =
        UpdateOptions.<String>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();

    Assert.assertEquals(
        "func", WorkflowClient.update(workflow::func, updateOptions).getResultAsync().get());
    Assert.assertEquals(
        "input",
        WorkflowClient.update(workflow::func1, "input", updateOptions).getResultAsync().get());
    Assert.assertEquals(
        "input2",
        WorkflowClient.update(workflow::func2, "input", 2, updateOptions).getResultAsync().get());
    Assert.assertEquals(
        "input23",
        WorkflowClient.update(workflow::func3, "input", 2, 3, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input234",
        WorkflowClient.update(workflow::func4, "input", 2, 3, 4, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input2345",
        WorkflowClient.update(workflow::func5, "input", 2, 3, 4, 5, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input23456",
        WorkflowClient.update(workflow::func6, "input", 2, 3, 4, 5, 6, updateOptions)
            .getResultAsync()
            .get());

    UpdateOptions<Void> updateVoidOptions =
        UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();
    WorkflowClient.update(workflow::proc, updateVoidOptions).getResultAsync().get();
    WorkflowClient.update(workflow::proc1, "input", updateVoidOptions).getResultAsync().get();
    WorkflowClient.update(workflow::proc2, "input", 2, updateVoidOptions).getResultAsync().get();
    WorkflowClient.update(workflow::proc3, "input", 2, 3, updateVoidOptions).getResultAsync().get();
    WorkflowClient.update(workflow::proc4, "input", 2, 3, 4, updateVoidOptions)
        .getResultAsync()
        .get();
    WorkflowClient.update(workflow::proc5, "input", 2, 3, 4, 5, updateVoidOptions)
        .getResultAsync()
        .get();
    WorkflowClient.update(workflow::proc6, "input", 2, 3, 4, 5, 6, updateVoidOptions)
        .getResultAsync()
        .get();

    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("procinputinput2input23input234input2345input23456", result);
  }
}
