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

import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

import com.google.common.collect.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.common.ProtoEnumNameUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class IncorrectUpsertSearchAttributesTest {
  private static final String DEFAULT_KEY_INTEGER = "CustomIntField";

  private static final String TEST_UNKNOWN_KEY = "UnknownKey";
  private static final String TEST_UNKNOWN_VALUE = "val";

  private static Map<String, Object> SEARCH_ATTRIBUTES;
  private static final AtomicBoolean FIRST_WFT_ATTEMPT = new AtomicBoolean();

  @Before
  public void setUp() {
    FIRST_WFT_ATTEMPT.set(true);
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(UpsertingWorkflow.class).build();

  @Test
  public void searchAttributeIsNotRegistered() {
    final String WORKFLOW_ID = "workflow-with-non-existing-sa-" + new Random().nextInt();
    SEARCH_ATTRIBUTES = ImmutableMap.of(TEST_UNKNOWN_KEY, TEST_UNKNOWN_VALUE);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowId(WORKFLOW_ID)
            .build();

    TestWorkflows.PrimitiveWorkflow stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    stubF.execute();

    History history =
        testWorkflowRule.getHistory(
            WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build());
    Optional<HistoryEvent> wftFailedEvent =
        history.getEventsList().stream()
            .filter(event -> event.getEventType().equals(EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED))
            .findFirst();
    assertTrue(wftFailedEvent.isPresent());
    WorkflowTaskFailedEventAttributes workflowTaskFailedEventAttributes =
        wftFailedEvent.get().getWorkflowTaskFailedEventAttributes();
    assertEquals(
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SEARCH_ATTRIBUTES,
        workflowTaskFailedEventAttributes.getCause());
    assertEquals(
        ProtoEnumNameUtils.uniqueToSimplifiedName(
                WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SEARCH_ATTRIBUTES)
            + ": search attribute UnknownKey is not defined",
        workflowTaskFailedEventAttributes.getFailure().getMessage());
  }

  @Test
  public void searchAttributeIsIncorrectValueType() {
    final String WORKFLOW_ID = "workflow-with-sa-incorrect-value-type-" + new Random().nextInt();
    SEARCH_ATTRIBUTES = ImmutableMap.of(DEFAULT_KEY_INTEGER, "this_is_string_and_not_an_int");

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowId(WORKFLOW_ID)
            .build();

    TestWorkflows.PrimitiveWorkflow stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    stubF.execute();

    History history =
        testWorkflowRule.getHistory(
            WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build());
    Optional<HistoryEvent> wftFailedEvent =
        history.getEventsList().stream()
            .filter(event -> event.getEventType().equals(EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED))
            .findFirst();
    assertTrue(wftFailedEvent.isPresent());
    WorkflowTaskFailedEventAttributes workflowTaskFailedEventAttributes =
        wftFailedEvent.get().getWorkflowTaskFailedEventAttributes();
    assertEquals(
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SEARCH_ATTRIBUTES,
        workflowTaskFailedEventAttributes.getCause());
    assertThat(
        workflowTaskFailedEventAttributes.getFailure().getMessage(),
        startsWith(
            ProtoEnumNameUtils.uniqueToSimplifiedName(
                    WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_BAD_SEARCH_ATTRIBUTES)
                + ": invalid value for search attribute CustomIntField of type Int"));
  }

  public static class UpsertingWorkflow implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {
      // try to set incorrect search attributes on the first attempt
      if (FIRST_WFT_ATTEMPT.getAndSet(false)) {
        Workflow.upsertSearchAttributes(SEARCH_ATTRIBUTES);
      }
    }
  }
}
