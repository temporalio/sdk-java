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

package io.temporal.workflow;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdReusePolicyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestMultiArgWorkflowImpl.class).build();

  @Test
  public void testWorkflowIdResuePolicy() {
    // When WorkflowIdReusePolicy is not AllowDuplicate the semantics is to get result for the
    // previous run.
    String workflowId = UUID.randomUUID().toString();
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .setWorkflowId(workflowId)
            .build();
    Test1ArgWorkflowFunc stubF1_1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
    Assert.assertEquals("1", stubF1_1.func1("1"));
    Test1ArgWorkflowFunc stubF1_2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
    Assert.assertEquals("1", stubF1_2.func1("2"));

    // Setting WorkflowIdReusePolicy to AllowDuplicate will trigger new run.
    workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .setWorkflowId(workflowId)
            .build();
    Test1ArgWorkflowFunc stubF1_3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
    Assert.assertEquals("2", stubF1_3.func1("2"));

    // Setting WorkflowIdReusePolicy to RejectDuplicate or AllowDuplicateFailedOnly does not work as
    // expected. See https://github.com/uber/cadence-java-client/issues/295.
  }
}
