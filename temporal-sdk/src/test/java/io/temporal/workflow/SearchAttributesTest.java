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
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SearchAttributesTest {

  private static final Map<String, Object> searchAttributes = new HashMap<>();
  private static final String TEST_KEY_STRING = "NamespaceKey";
  private static final String TEST_VALUE_STRING = "Namespace";
  private static final String TEST_KEY_INTEGER = "StateTransitionCount";
  private static final Integer TEST_VALUE_INTEGER = 1;
  // Custom fields
  private static final String TEST_KEY_DATE_TIME = "CustomDatetimeField";
  private static final LocalDateTime TEST_VALUE_DATE_TIME = LocalDateTime.now();
  private static final String TEST_KEY_DOUBLE = "CustomDoubleField";
  private static final Double TEST_VALUE_DOUBLE = 1.23;
  private static final String TEST_KEY_BOOL = "CustomBoolField";
  private static final Boolean TEST_VALUE_BOOL = true;

  @Before
  public void setUp() {
    // add more type to test
    searchAttributes.put(TEST_KEY_STRING, TEST_VALUE_STRING);
    searchAttributes.put(TEST_KEY_INTEGER, TEST_VALUE_INTEGER);
    searchAttributes.put(TEST_KEY_DATE_TIME, TEST_VALUE_DATE_TIME);
    searchAttributes.put(TEST_KEY_BOOL, TEST_VALUE_BOOL);
    searchAttributes.put(TEST_KEY_DOUBLE, TEST_VALUE_DOUBLE);
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestMultiArgWorkflowImpl.class, TestParentWorkflow.class, TestChild.class)
          .build();

  @Test
  public void testSearchAttributes() {

    WorkflowOptions workflowOptions =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
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

    Map<String, Object> fieldsMap =
        SearchAttributesUtil.deserializeToObjectMap(searchAttrFromEvent);
    assertEquals(TEST_VALUE_STRING, fieldsMap.get(TEST_KEY_STRING));
    assertEquals(TEST_VALUE_INTEGER, fieldsMap.get(TEST_KEY_INTEGER));
    assertEquals(TEST_VALUE_DATE_TIME, fieldsMap.get(TEST_KEY_DATE_TIME));
    assertEquals(TEST_VALUE_BOOL, fieldsMap.get(TEST_KEY_BOOL));
    assertEquals(TEST_VALUE_DOUBLE, fieldsMap.get(TEST_KEY_DOUBLE));
  }

  @Test
  public void testSearchAttributesPresentInChildWorkflow() {
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
      // Check that search attributes are inherited by child workflows.
      assertNotNull(Workflow.getInfo().getSearchAttributes());
    }
  }
}
