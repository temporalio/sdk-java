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
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ExecuteTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class);
    Assert.assertEquals("func", WorkflowClient.execute(stubF::func).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class);
    Assert.assertEquals(1, (int) WorkflowClient.execute(stubF1::func1, 1).get());
    Assert.assertEquals(1, stubF1.func1(1)); // Check that duplicated start just returns the result.
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2 stubF2 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc2.class);
    Assert.assertEquals("12", WorkflowClient.execute(stubF2::func2, "1", 2).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3 stubF3 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc3.class);
    Assert.assertEquals("123", WorkflowClient.execute(stubF3::func3, "1", 2, 3).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4 stubF4 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc4.class);
    Assert.assertEquals("1234", WorkflowClient.execute(stubF4::func4, "1", 2, 3, 4).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5 stubF5 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc5.class);
    Assert.assertEquals("12345", WorkflowClient.execute(stubF5::func5, "1", 2, 3, 4, 5).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6 stubF6 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc6.class);
    Assert.assertEquals("123456", WorkflowClient.execute(stubF6::func6, "1", 2, 3, 4, 5, 6).get());
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc stubP =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc.class);
    WorkflowClient.execute(stubP::proc).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1 stubP1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc1.class);
    WorkflowClient.execute(stubP1::proc1, "1").get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2 stubP2 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc2.class);
    WorkflowClient.execute(stubP2::proc2, "1", 2).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3 stubP3 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc3.class);
    WorkflowClient.execute(stubP3::proc3, "1", 2, 3).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4 stubP4 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc4.class);
    WorkflowClient.execute(stubP4::proc4, "1", 2, 3, 4).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5 stubP5 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc5.class);
    WorkflowClient.execute(stubP5::proc5, "1", 2, 3, 4, 5).get();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6 stubP6 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsProc6.class);
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
