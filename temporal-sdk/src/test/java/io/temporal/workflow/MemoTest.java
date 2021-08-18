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
import static org.junit.Assert.assertNotNull;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.GsonJsonPayloadConverter;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class MemoTest {

  private static final String MEMO_KEY = "testKey";
  private static final String MEMO_VALUE = "testValue";
  private static final Map<String, Object> MEMO = ImmutableMap.of(MEMO_KEY, MEMO_VALUE);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMultiArgWorkflowImpl.class, WorkflowImpl.class)
          .build();

  @Test
  public void testMemo() {
    if (testWorkflowRule.getTestEnvironment() == null) {
      return;
    }

    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(MEMO)
            .build();

    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestNoArgsWorkflowFunc.class, workflowOptions);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowExecutionUtils.getHistoryPage(
            testWorkflowRule.getTestEnvironment().getWorkflowService(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    Memo memoFromEvent = startEvent.getWorkflowExecutionStartedEventAttributes().getMemo();
    Payload memoBytes = memoFromEvent.getFieldsMap().get(MEMO_KEY);
    String memoRetrieved =
        GsonJsonPayloadConverter.getInstance().fromData(memoBytes, String.class, String.class);
    assertEquals(MEMO_VALUE, memoRetrieved);
  }

  @Test
  public void testMemoInWorkflow() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(MEMO)
            .build();

    NoArgsWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(NoArgsWorkflow.class, workflowOptions);
    workflow.execute();
  }

  public static class WorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      assertNotNull(Workflow.getMemo(MEMO_KEY));
    }
  }
}
