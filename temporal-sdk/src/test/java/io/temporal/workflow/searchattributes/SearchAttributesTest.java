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

package io.temporal.workflow.searchattributes;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.internal.client.WorkflowClientHelper;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;

public class SearchAttributesTest {
  private static final String TEST_KEY_STRING = "CustomStringField";
  private static final String TEST_VALUE_STRING = NAMESPACE;
  private static final String TEST_KEY_INTEGER = "CustomIntField";
  private static final Long TEST_VALUE_LONG = 7L;
  private static final String TEST_KEY_DATE_TIME = "CustomDatetimeField";
  private static final OffsetDateTime TEST_VALUE_DATE_TIME = OffsetDateTime.now();
  private static final String TEST_KEY_DOUBLE = "CustomDoubleField";
  private static final Double TEST_VALUE_DOUBLE = 1.23;
  private static final String TEST_KEY_BOOL = "CustomBoolField";
  private static final Boolean TEST_VALUE_BOOL = true;
  private static final String TEST_NEW_KEY = "NewKey";
  private static final String TEST_NEW_VALUE = "NewVal";
  private static final String TEST_UNKNOWN_KEY = "UnknownKey";
  private static final String TEST_UNKNOWN_VALUE = "val";

  private static final Map<String, Object> DEFAULT_SEARCH_ATTRIBUTES =
      Collections.unmodifiableMap(
          new HashMap<String, Object>() {
            {
              put(TEST_KEY_STRING, TEST_VALUE_STRING);
              put(TEST_KEY_INTEGER, TEST_VALUE_LONG);
              put(TEST_KEY_DOUBLE, TEST_VALUE_DOUBLE);
              put(TEST_KEY_BOOL, TEST_VALUE_BOOL);
              put(TEST_KEY_DATE_TIME, TEST_VALUE_DATE_TIME);
            }
          });

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestMultiArgWorkflowImpl.class, TestParentWorkflow.class, TestChild.class)
          .registerSearchAttribute(TEST_NEW_KEY, IndexedValueType.INDEXED_VALUE_TYPE_TEXT)
          .build();

  @Test
  public void testDefaultTestSearchAttributes() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES)
            .build();

    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestNoArgsWorkflowFunc.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(DEFAULT_SEARCH_ATTRIBUTES), fieldsMap).areEqual());
  }

  @Test
  public void testListInDefaultTestSearchAttributes() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.replace(TEST_KEY_INTEGER, Lists.newArrayList(1L, 2L));

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();

    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestNoArgsWorkflowFunc.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(searchAttributes), fieldsMap).areEqual());
  }

  @Test
  public void testCustomSearchAttributes() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.put(TEST_NEW_KEY, TEST_NEW_VALUE);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();

    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestNoArgsWorkflowFunc.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, List<?>> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertTrue(Maps.difference(wrapValues(searchAttributes), fieldsMap).areEqual());
  }

  @Test
  public void testInvalidSearchAttributeKey() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.put(TEST_UNKNOWN_KEY, TEST_VALUE_BOOL);
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();
    TestNoArgsWorkflowFunc unregisteredKeyStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestNoArgsWorkflowFunc.class, options);
    try {
      WorkflowClient.start(unregisteredKeyStub::func);
      fail();
    } catch (WorkflowServiceException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
      assertEquals(Status.Code.INVALID_ARGUMENT, sre.getStatus().getCode());
    }
  }

  @Test
  public void testInvalidSearchAttributeType() {
    Map<String, Object> searchAttributes = new HashMap<>(DEFAULT_SEARCH_ATTRIBUTES);
    searchAttributes.replace(TEST_KEY_INTEGER, TEST_VALUE_BOOL);
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();
    TestNoArgsWorkflowFunc unsupportedTypeStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestNoArgsWorkflowFunc.class, options);
    try {
      WorkflowClient.start(unsupportedTypeStub::func);
      fail();
    } catch (WorkflowServiceException exception) {
      assertTrue(exception.getCause() instanceof StatusRuntimeException);
      StatusRuntimeException e = (StatusRuntimeException) exception.getCause();
      assertEquals(e.getStatus().getCode(), Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void testSearchAttributesPresentInChildWorkflow() {
    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    client.execute();
  }

  private static Map<String, List<?>> wrapValues(Map<String, Object> map) {
    return Maps.transformValues(
        map,
        o ->
            o instanceof Collection
                ? new ArrayList<>((Collection<?>) o)
                : Collections.singletonList(o));
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
          ChildWorkflowOptions.newBuilder().setSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES).build();
      TestChildWorkflow child = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      child.execute();
    }
  }

  public static class TestChild implements TestChildWorkflow {
    @Override
    public void execute() {
      // Check that search attributes are inherited by child workflows.
      assertEquals(wrapValues(DEFAULT_SEARCH_ATTRIBUTES), Workflow.getSearchAttributes());
    }
  }
}
