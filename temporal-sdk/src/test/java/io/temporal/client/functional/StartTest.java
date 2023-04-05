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

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.*;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class StartTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestMultiArgWorkflowImpl.class).build();

  @Test
  public void startNoArgFuncWithRejectDuplicate() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestNoArgsWorkflowFunc.class, workflowOptions);
    assertResult("func", WorkflowClient.start(stubF::func));
    Assert.assertEquals(
        "func", stubF.func()); // Check that duplicated start just returns the result.
  }

  @Test
  public void startOneArgsFuncWithDefault() {
    // TODO why it doesn't work with external service?
    if (!SDKTestWorkflowRule.useExternalService) {
      WorkflowOptions options =
          WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
      Test1ArgWorkflowFunc stubF1 =
          testWorkflowRule.getWorkflowClient().newWorkflowStub(Test1ArgWorkflowFunc.class, options);
      // Use worker that polls on a task queue configured through @WorkflowMethod annotation of
      // func1
      assertResult(1, WorkflowClient.start(stubF1::func1, 1));
      Assert.assertEquals(
          1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    }
  }

  @Test
  public void startTwoArgsFuncWithAllowDuplicate() {
    // Check that duplicated start is not allowed for AllowDuplicate IdReusePolicy
    Test2ArgWorkflowFunc stubF2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                Test2ArgWorkflowFunc.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                    .build());
    assertResult("12", WorkflowClient.start(stubF2::func2, "1", 2));
    try {
      stubF2.func2("1", 2);
      Assert.fail("unreachable");
    } catch (IllegalStateException e) {
      // expected
    }
  }

  @Test
  public void testStartFunc() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();

    Test3ArgWorkflowFunc stubF3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test3ArgWorkflowFunc.class, workflowOptions);
    WorkflowExecution f3Execution = WorkflowClient.start(stubF3::func3, "1", 2, 3);
    Test4ArgWorkflowFunc stubF4 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test4ArgWorkflowFunc.class, workflowOptions);
    WorkflowExecution f4Execution = WorkflowClient.start(stubF4::func4, "1", 2, 3, 4);
    Test5ArgWorkflowFunc stubF5 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test5ArgWorkflowFunc.class, workflowOptions);
    WorkflowExecution f5Execution = WorkflowClient.start(stubF5::func5, "1", 2, 3, 4, 5);
    Test6ArgWorkflowFunc stubF6 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test6ArgWorkflowFunc.class, workflowOptions);
    WorkflowExecution f6Execution = WorkflowClient.start(stubF6::func6, "1", 2, 3, 4, 5, 6);

    assertResult("123", f3Execution);
    assertResult("1234", f4Execution);
    assertResult("12345", f5Execution);
    assertResult("123456", f6Execution);
  }

  @Test
  public void testStartProc() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();

    TestNoArgsWorkflowProc stubP =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestNoArgsWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP::proc));
    Test1ArgWorkflowProc stubP1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP1::proc1, "1"));
    Test2ArgWorkflowProc stubP2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test2ArgWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP2::proc2, "1", 2));
    Test3ArgWorkflowProc stubP3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test3ArgWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP3::proc3, "1", 2, 3));
    Test4ArgWorkflowProc stubP4 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test4ArgWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP4::proc4, "1", 2, 3, 4));
    Test5ArgWorkflowProc stubP5 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test5ArgWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP5::proc5, "1", 2, 3, 4, 5));
    Test6ArgWorkflowProc stubP6 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test6ArgWorkflowProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP6::proc6, "1", 2, 3, 4, 5, 6));

    Assert.assertEquals("proc", stubP.query());
    Assert.assertEquals("1", stubP1.query());
    Assert.assertEquals("12", stubP2.query());
    Assert.assertEquals("123", stubP3.query());
    Assert.assertEquals("1234", stubP4.query());
    Assert.assertEquals("12345", stubP5.query());
    Assert.assertEquals("123456", stubP6.query());
  }

  private void assertResult(String expected, WorkflowExecution execution) {
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals(expected, result);
  }

  private void assertResult(int expected, WorkflowExecution execution) {
    int result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(int.class);
    assertEquals(expected, result);
  }

  private void waitForProc(WorkflowExecution execution) {
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(execution, Optional.empty())
        .getResult(Void.class);
  }
}
