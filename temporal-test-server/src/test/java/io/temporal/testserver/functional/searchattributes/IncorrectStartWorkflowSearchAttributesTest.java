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

package io.temporal.testserver.functional.searchattributes;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.internal.client.WorkflowClientHelper;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;

public class IncorrectStartWorkflowSearchAttributesTest {
  private static final String DEFAULT_KEY_INTEGER = "CustomIntField";

  private static final String TEST_UNKNOWN_KEY = "UnknownKey";
  private static final String TEST_UNKNOWN_VALUE = "val";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(DummyWorkflow.class).build();

  @Test
  public void searchAttributeIsNotRegistered() {
    final String WORKFLOW_ID = "workflow-with-non-existing-sa";
    Map<String, Object> searchAttributes = ImmutableMap.of(TEST_UNKNOWN_KEY, TEST_UNKNOWN_VALUE);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .setWorkflowId(WORKFLOW_ID)
            .build();

    TestWorkflows.PrimitiveWorkflow stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    WorkflowServiceException exception =
        assertThrows(WorkflowServiceException.class, () -> WorkflowClient.start(stubF::execute));
    assertThat(exception.getCause(), instanceOf(StatusRuntimeException.class));
    Status status = ((StatusRuntimeException) exception.getCause()).getStatus();
    assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
    assertEquals("search attribute UnknownKey is not defined", status.getDescription());

    StatusRuntimeException historyException =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                WorkflowClientHelper.getHistoryPage(
                    testWorkflowRule.getWorkflowServiceStubs(),
                    SDKTestWorkflowRule.NAMESPACE,
                    WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build(),
                    ByteString.EMPTY,
                    new NoopScope()));
    assertEquals(
        "No workflows should have been started",
        Status.NOT_FOUND.getCode(),
        historyException.getStatus().getCode());
  }

  @Test
  public void searchAttributeIsIncorrectValueType() {
    final String WORKFLOW_ID = "workflow-with-sa-incorrect-value-type";
    Map<String, Object> searchAttributes =
        ImmutableMap.of(DEFAULT_KEY_INTEGER, "this_is_string_and_not_an_int");

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setSearchAttributes(searchAttributes)
            .setWorkflowId(WORKFLOW_ID)
            .build();

    TestWorkflows.PrimitiveWorkflow stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    WorkflowServiceException exception =
        assertThrows(WorkflowServiceException.class, () -> WorkflowClient.start(stubF::execute));
    assertThat(exception.getCause(), instanceOf(StatusRuntimeException.class));
    Status status = ((StatusRuntimeException) exception.getCause()).getStatus();
    assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
    assertThat(
        status.getDescription(),
        startsWith("invalid value for search attribute CustomIntField of type Int"));

    StatusRuntimeException historyException =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                WorkflowClientHelper.getHistoryPage(
                    testWorkflowRule.getWorkflowServiceStubs(),
                    SDKTestWorkflowRule.NAMESPACE,
                    WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build(),
                    ByteString.EMPTY,
                    new NoopScope()));
    assertEquals(
        "No workflows should have been started",
        Status.NOT_FOUND.getCode(),
        historyException.getStatus().getCode());
  }

  public static class DummyWorkflow implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {}
  }
}
