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

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.GsonJsonPayloadConverter;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestOptions;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class MemoTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testMemo() {
    if (testWorkflowRule.getTestEnvironment() != null) {
      String testMemoKey = "testKey";
      String testMemoValue = "testValue";
      Map<String, Object> memo = new HashMap<String, Object>();
      memo.put(testMemoKey, testMemoValue);

      WorkflowOptions workflowOptions =
          TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
              .toBuilder()
              .setMemo(memo)
              .build();
      TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
          testWorkflowRule
              .getWorkflowClient()
              .newWorkflowStub(
                  TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class,
                  workflowOptions);
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
      Payload memoBytes = memoFromEvent.getFieldsMap().get(testMemoKey);
      String memoRetrieved =
          GsonJsonPayloadConverter.getInstance().fromData(memoBytes, String.class, String.class);
      Assert.assertEquals(testMemoValue, memoRetrieved);
    }
  }
}
