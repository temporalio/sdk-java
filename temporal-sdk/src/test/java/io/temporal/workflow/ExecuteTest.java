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

import io.temporal.client.WorkflowClient;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.*;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ExecuteTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestMultiArgWorkflowImpl.class).build();

  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestNoArgsWorkflowFunc.class);
    Assert.assertEquals("func", WorkflowClient.execute(stubF::func).get());
    Test1ArgWorkflowFunc stubF1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test1ArgWorkflowFunc.class);
    Assert.assertEquals(1, (int) WorkflowClient.execute(stubF1::func1, 1).get());
    Assert.assertEquals(1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    Test2ArgWorkflowFunc stubF2 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test2ArgWorkflowFunc.class);
    Assert.assertEquals("12", WorkflowClient.execute(stubF2::func2, "1", 2).get());
    Test3ArgWorkflowFunc stubF3 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test3ArgWorkflowFunc.class);
    Assert.assertEquals("123", WorkflowClient.execute(stubF3::func3, "1", 2, 3).get());
    Test4ArgWorkflowFunc stubF4 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test4ArgWorkflowFunc.class);
    Assert.assertEquals("1234", WorkflowClient.execute(stubF4::func4, "1", 2, 3, 4).get());
    Test5ArgWorkflowFunc stubF5 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test5ArgWorkflowFunc.class);
    Assert.assertEquals("12345", WorkflowClient.execute(stubF5::func5, "1", 2, 3, 4, 5).get());
    Test6ArgWorkflowFunc stubF6 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test6ArgWorkflowFunc.class);
    Assert.assertEquals("123456", WorkflowClient.execute(stubF6::func6, "1", 2, 3, 4, 5, 6).get());
    TestNoArgsWorkflowProc stubP =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestNoArgsWorkflowProc.class);
    WorkflowClient.execute(stubP::proc).get();
    Test1ArgWorkflowProc stubP1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test1ArgWorkflowProc.class);
    WorkflowClient.execute(stubP1::proc1, "1").get();
    Test2ArgWorkflowProc stubP2 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test2ArgWorkflowProc.class);
    WorkflowClient.execute(stubP2::proc2, "1", 2).get();
    Test3ArgWorkflowProc stubP3 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test3ArgWorkflowProc.class);
    WorkflowClient.execute(stubP3::proc3, "1", 2, 3).get();
    Test4ArgWorkflowProc stubP4 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test4ArgWorkflowProc.class);
    WorkflowClient.execute(stubP4::proc4, "1", 2, 3, 4).get();
    Test5ArgWorkflowProc stubP5 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test5ArgWorkflowProc.class);
    WorkflowClient.execute(stubP5::proc5, "1", 2, 3, 4, 5).get();
    Test6ArgWorkflowProc stubP6 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(Test6ArgWorkflowProc.class);
    WorkflowClient.execute(stubP6::proc6, "1", 2, 3, 4, 5, 6).get();

    Assert.assertEquals("proc", stubP.query());
    Assert.assertEquals("1", stubP1.query());
    Assert.assertEquals("12", stubP2.query());
    Assert.assertEquals("123", stubP3.query());
    Assert.assertEquals("1234", stubP4.query());
    Assert.assertEquals("12345", stubP5.query());
    Assert.assertEquals("123456", stubP6.query());
  }
}
