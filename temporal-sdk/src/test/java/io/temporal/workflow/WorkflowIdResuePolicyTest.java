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

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestOptions;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdResuePolicyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class)
          .build();

  @Test
  public void testWorkflowIdResuePolicy() {
    // When WorkflowIdReusePolicy is not AllowDuplicate the semantics is to get result for the
    // previous run.
    String workflowId = UUID.randomUUID().toString();
    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .setWorkflowId(workflowId)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1_1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    Assert.assertEquals(1, stubF1_1.func1(1));
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1_2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    Assert.assertEquals(1, stubF1_2.func1(2));

    // Setting WorkflowIdReusePolicy to AllowDuplicate will trigger new run.
    workflowOptions =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .setWorkflowId(workflowId)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1 stubF1_3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc1.class, workflowOptions);
    Assert.assertEquals(2, stubF1_3.func1(2));

    // Setting WorkflowIdReusePolicy to RejectDuplicate or AllowDuplicateFailedOnly does not work as
    // expected. See https://github.com/uber/cadence-java-client/issues/295.
  }
}
