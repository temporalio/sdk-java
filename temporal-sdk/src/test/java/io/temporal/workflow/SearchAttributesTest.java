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

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.common.converter.SearchAttributesUtil;
import io.temporal.common.converter.SearchAttributesUtil.RegisteredSearchAttributes;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SearchAttributesTest {

  private static final Map<String, Object> searchAttributes = new ConcurrentHashMap<>();
  private static final String TEST_KEY_STRING = RegisteredSearchAttributes.CustomStringField.name();
  private static final String TEST_VALUE_STRING = NAMESPACE;
  private static final String TEST_KEY_INTEGER = RegisteredSearchAttributes.CustomIntField.name();
  private static final Integer TEST_VALUE_INTEGER = 1;
  private static final String TEST_KEY_DATE_TIME =
      RegisteredSearchAttributes.CustomDatetimeField.name();
  private static final LocalDateTime TEST_VALUE_DATE_TIME = LocalDateTime.now();
  private static final String TEST_KEY_DOUBLE = RegisteredSearchAttributes.CustomDoubleField.name();
  private static final Double TEST_VALUE_DOUBLE = 1.23;
  private static final String TEST_KEY_BOOL = RegisteredSearchAttributes.CustomBoolField.name();
  private static final Boolean TEST_VALUE_BOOL = true;
  private static final String TEST_UNKNOWN_KEY = "UnknownKey";
  private static final String TEST_UNKNOWN_VALUE = "UnknownVal";
  private static final String TEST_UNSUPPORTED_TYPE_KEY =
      RegisteredSearchAttributes.CustomStringField.name();
  private static final Duration TEST_UNSUPPORTED_TYPE_VALUE = Duration.ZERO;
  private static WorkflowOptions options;

  @Before
  public void setUp() {
    searchAttributes.put(TEST_KEY_STRING, TEST_VALUE_STRING);
    searchAttributes.put(TEST_KEY_INTEGER, TEST_VALUE_INTEGER);
    searchAttributes.put(TEST_KEY_DATE_TIME, TEST_VALUE_DATE_TIME);
    searchAttributes.put(TEST_KEY_BOOL, TEST_VALUE_BOOL);
    searchAttributes.put(TEST_KEY_DOUBLE, TEST_VALUE_DOUBLE);
    options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .build();
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setNamespace(NAMESPACE)
          .setWorkflowTypes(
              TestMultiArgWorkflowImpl.class, TestParentWorkflow.class, TestChild.class)
          .build();

  @Test
  public void testSearchAttributes() {
    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestNoArgsWorkflowFunc.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowExecutionUtils.getHistoryPage(
            testWorkflowRule.getTestEnvironment().getWorkflowService(),
            NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    Map<String, Object> fieldsMap = SearchAttributesUtil.decode(searchAttrFromEvent);
    assertEquals(searchAttributes, fieldsMap);
  }

  @Test
  public void testInvalidSearchAttributeKey() {
    searchAttributes.put(TEST_UNKNOWN_KEY, TEST_UNKNOWN_VALUE);
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
    searchAttributes.remove(TEST_UNKNOWN_KEY, TEST_UNKNOWN_VALUE);
  }

  @Test
  public void testInvalidSearchAttributeType() {
    assumeTrue(testWorkflowRule.isUseExternalService());

    searchAttributes.put(TEST_UNSUPPORTED_TYPE_KEY, TEST_UNSUPPORTED_TYPE_VALUE);
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
    searchAttributes.remove(TEST_UNSUPPORTED_TYPE_KEY);
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
      assertEquals(Workflow.getSearchAttributes(), searchAttributes);
    }
  }
}
