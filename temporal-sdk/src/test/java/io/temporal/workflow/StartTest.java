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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestOptions;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class StartTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testStart() {
    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
    assertResult("func", WorkflowClient.start(stubF::func));
    Assert.assertEquals(
        "func", stubF.func()); // Check that duplicated start just returns the result.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, options);

    if (!SDKTestWorkflowRule.useExternalService) {
      // Use worker that polls on a task queue configured through @WorkflowMethod annotation of
      // func1
      assertResult(1, WorkflowClient.start(stubF1::func1, 1));
      Assert.assertEquals(
          1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    }
    // Check that duplicated start is not allowed for AllowDuplicate IdReusePolicy
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class,
                TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
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
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3 stubF3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3.class, workflowOptions);
    assertResult("123", WorkflowClient.start(stubF3::func3, "1", 2, 3));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4 stubF4 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4.class, workflowOptions);
    assertResult("1234", WorkflowClient.start(stubF4::func4, "1", 2, 3, 4));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5 stubF5 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5.class, workflowOptions);
    assertResult("12345", WorkflowClient.start(stubF5::func5, "1", 2, 3, 4, 5));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6 stubF6 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6.class, workflowOptions);
    assertResult("123456", WorkflowClient.start(stubF6::func6, "1", 2, 3, 4, 5, 6));

    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc stubP =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP::proc));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1 stubP1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP1::proc1, "1"));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2 stubP2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP2::proc2, "1", 2));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3 stubP3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP3::proc3, "1", 2, 3));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4 stubP4 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP4::proc4, "1", 2, 3, 4));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5 stubP5 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5.class, workflowOptions);
    waitForProc(WorkflowClient.start(stubP5::proc5, "1", 2, 3, 4, 5));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6 stubP6 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6.class, workflowOptions);
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
