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

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiargdsWorkflowFunctions;
import io.temporal.workflow.shared.TestOptions;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class SearchAttributesTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsImpl.class)
          .build();

  @Test
  public void testSearchAttributes() {
    if (SDKTestWorkflowRule.useExternalService) {
      return;
    }
    String testKeyString = "CustomKeywordField";
    String testValueString = "testKeyword";
    String testKeyInteger = "CustomIntField";
    Integer testValueInteger = 1;
    String testKeyDateTime = "CustomDatetimeField";
    LocalDateTime testValueDateTime = LocalDateTime.now();
    String testKeyBool = "CustomBoolField";
    Boolean testValueBool = true;
    String testKeyDouble = "CustomDoubleField";
    Double testValueDouble = 1.23;

    // add more type to test
    Map<String, Object> searchAttr = new HashMap<>();
    searchAttr.put(testKeyString, testValueString);
    searchAttr.put(testKeyInteger, testValueInteger);
    searchAttr.put(testKeyDateTime, testValueDateTime);
    searchAttr.put(testKeyBool, testValueBool);
    searchAttr.put(testKeyDouble, testValueDouble);

    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttr)
            .build();
    TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestMultiargdsWorkflowFunctions.TestMultiargsWorkflowsFunc.class, workflowOptions);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowExecutionUtils.getHistoryPage(
            testWorkflowRule.getTestEnvironment().getWorkflowService(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, Payload> fieldsMap = searchAttrFromEvent.getIndexedFieldsMap();
    Payload searchAttrStringBytes = fieldsMap.get(testKeyString);
    DataConverter converter = DataConverter.getDefaultInstance();
    String retrievedString =
        converter.fromPayload(searchAttrStringBytes, String.class, String.class);
    assertEquals(testValueString, retrievedString);
    Payload searchAttrIntegerBytes = fieldsMap.get(testKeyInteger);
    Integer retrievedInteger =
        converter.fromPayload(searchAttrIntegerBytes, Integer.class, Integer.class);
    assertEquals(testValueInteger, retrievedInteger);
    Payload searchAttrDateTimeBytes = fieldsMap.get(testKeyDateTime);
    LocalDateTime retrievedDateTime =
        converter.fromPayload(searchAttrDateTimeBytes, LocalDateTime.class, LocalDateTime.class);
    assertEquals(testValueDateTime, retrievedDateTime);
    Payload searchAttrBoolBytes = fieldsMap.get(testKeyBool);
    Boolean retrievedBool =
        converter.fromPayload(searchAttrBoolBytes, Boolean.class, Boolean.class);
    assertEquals(testValueBool, retrievedBool);
    Payload searchAttrDoubleBytes = fieldsMap.get(testKeyDouble);
    Double retrievedDouble =
        converter.fromPayload(searchAttrDoubleBytes, Double.class, Double.class);
    assertEquals(testValueDouble, retrievedDouble);
  }
}
