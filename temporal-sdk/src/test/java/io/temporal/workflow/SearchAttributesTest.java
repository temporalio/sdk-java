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
import static org.junit.Assert.assertTrue;

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
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SearchAttributesTest {

  private static Map<String, Object> searchAttributes = new HashMap<>();
  private static String testKeyString = "CustomKeywordField";
  private static String testValueString = "testKeyword";
  private static String testKeyInteger = "CustomIntField";
  private static Integer testValueInteger = 1;
  private static String testKeyDateTime = "CustomDatetimeField";
  private static LocalDateTime testValueDateTime = LocalDateTime.now();
  private static String testKeyBool = "CustomBoolField";
  private static Boolean testValueBool = true;
  private static String testKeyDouble = "CustomDoubleField";
  private static Double testValueDouble = 1.23;

  @Before
  public void setUp() {
    // add more type to test
    searchAttributes = new HashMap<>();
    searchAttributes.put(testKeyString, testValueString);
    searchAttributes.put(testKeyInteger, testValueInteger);
    searchAttributes.put(testKeyDateTime, testValueDateTime);
    searchAttributes.put(testKeyBool, testValueBool);
    searchAttributes.put(testKeyDouble, testValueDouble);
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestMultiArgWorkflowImpl.class, TestParentWorkflow.class, TestChild.class)
          .build();

  @Test
  public void testSearchAttributes() {
    if (testWorkflowRule.isUseExternalService()) {
      // LocalDateTime fails to deserialize in the real service, with a message like
      // INVALID_ARGUMENT: 2021-08-26T13:21:52.059738 is not a valid value for search attribute
      // CustomDatetimeField of type Datetime
      // Tracked in https://github.com/temporalio/sdk-java/issues/673
      searchAttributes.remove(testKeyDateTime);
    }

    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
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
    if (!testWorkflowRule.isUseExternalService()) {
      LocalDateTime retrievedDateTime =
          converter.fromPayload(searchAttrDateTimeBytes, LocalDateTime.class, LocalDateTime.class);
      assertEquals(testValueDateTime, retrievedDateTime);
    }
    Payload searchAttrBoolBytes = fieldsMap.get(testKeyBool);
    Boolean retrievedBool =
        converter.fromPayload(searchAttrBoolBytes, Boolean.class, Boolean.class);
    assertEquals(testValueBool, retrievedBool);
    Payload searchAttrDoubleBytes = fieldsMap.get(testKeyDouble);
    Double retrievedDouble =
        converter.fromPayload(searchAttrDoubleBytes, Double.class, Double.class);
    assertEquals(testValueDouble, retrievedDouble);
  }

  @Test
  public void testSearchAttributesPresentInChildWorkflow() {
    // see testSearchAttributes() for explanation
    if (testWorkflowRule.isUseExternalService()) {
      searchAttributes.remove(testKeyDateTime);
    }
    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    client.execute();
  }

  @WorkflowInterface
  public interface TestChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestParentWorkflow implements NoArgsWorkflow {
    @Override
    public void execute() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setSearchAttributes(searchAttributes).build();
      TestChildWorkflow child = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      child.execute();
    }
  }

  public static class TestChild implements TestChildWorkflow {
    @Override
    public void execute() {
      assertTrue(Workflow.getInfo().getSearchAttributes() instanceof SearchAttributes);
    }
  }
}
