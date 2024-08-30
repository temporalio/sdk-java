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

package io.temporal.workflow.upsertMemoTests;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

public class UpsertMemoTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflow1Impl.class).build();

  @Test
  public void upsertMemo() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("memoValue2", result);
    // Verify that describeWorkflowExecution returns the correct final memo
    DescribeWorkflowExecutionResponse resp =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setExecution(WorkflowStub.fromTyped(workflowStub).getExecution())
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build());
    Map<String, Payload> memo =
        Collections.singletonMap(
            "memoKey2", DefaultDataConverter.newDefaultInstance().toPayload("memoValue2").get());
    Assert.assertEquals(memo, resp.getWorkflowExecutionInfo().getMemo().getFieldsMap());
  }

  public static class TestWorkflow1Impl implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      String memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertNull(memoVal);

      Workflow.upsertMemo(Collections.singletonMap("memoKey", "memoValue"));
      memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertEquals("memoValue", memoVal);

      Workflow.sleep(Duration.ofMillis(100));

      Workflow.upsertMemo(Collections.singletonMap("memoKey2", "memoValue2"));
      memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertEquals("memoValue", memoVal);

      Workflow.sleep(Duration.ofMillis(100));

      Workflow.upsertMemo(Collections.singletonMap("memoKey", null));
      memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertNull(memoVal);
      return Workflow.getMemo("memoKey2", String.class, String.class);
    }
  }
}
