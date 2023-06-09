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

package io.temporal.workflow.searchattributes;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.common.SearchAttributeKey;
import io.temporal.internal.client.WorkflowClientHelper;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/** Typed attribute translation of {@link SearchAttributesTest} */
public class TypedSearchAttributesTest {
  private static final SearchAttributeKey<List<String>> TEST_NEW_KEY =
      SearchAttributeKey.forKeywordList("NewKey");
  private static final List<String> TEST_NEW_VALUE = Arrays.asList("foo", "bar");
  private static final SearchAttributeKey<String> TEST_UNKNOWN_KEY =
      SearchAttributeKey.forText("UnknownKey");

  private static final io.temporal.common.SearchAttributes DEFAULT_SEARCH_ATTRIBUTES =
      io.temporal.common.SearchAttributes.newBuilder()
          .set(SearchAttributeKey.forKeyword("CustomStringField"), NAMESPACE)
          .set(SearchAttributeKey.forLong("CustomIntField"), 7L)
          .set(SearchAttributeKey.forDouble("CustomDoubleField"), 1.23)
          .set(SearchAttributeKey.forBoolean("CustomBoolField"), true)
          .set(SearchAttributeKey.forOffsetDateTime("CustomDatetimeField"), OffsetDateTime.now())
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class, TestParentWorkflow.class, TestChild.class)
          .registerSearchAttribute(TEST_NEW_KEY)
          .build();

  @Test
  public void defaultTestSearchAttributes() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTypedSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::execute);

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

    io.temporal.common.SearchAttributes decoded =
        SearchAttributesUtil.decodeTyped(searchAttrFromEvent);
    assertEquals(DEFAULT_SEARCH_ATTRIBUTES, decoded);
  }

  @Test
  public void defaultTestSearchAttributesSignalWithStart() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTypedSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);

    BatchRequest batchRequest = testWorkflowRule.getWorkflowClient().newSignalWithStartRequest();
    batchRequest.add(stubF::execute);
    batchRequest.add(stubF::signal, "signal");
    WorkflowExecution execution =
        testWorkflowRule.getWorkflowClient().signalWithStart(batchRequest);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            execution,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    SearchAttributes searchAttrFromEvent =
        startEvent.getWorkflowExecutionStartedEventAttributes().getSearchAttributes();

    io.temporal.common.SearchAttributes decoded =
        SearchAttributesUtil.decodeTyped(searchAttrFromEvent);
    assertEquals(DEFAULT_SEARCH_ATTRIBUTES, decoded);
  }

  @Test
  public void testCustomSearchAttributes() {
    io.temporal.common.SearchAttributes searchAttributes =
        io.temporal.common.SearchAttributes.newBuilder(DEFAULT_SEARCH_ATTRIBUTES)
            .set(TEST_NEW_KEY, TEST_NEW_VALUE)
            .build();

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTypedSearchAttributes(searchAttributes)
            .build();

    TestSignaledWorkflow stubF =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowExecution executionF = WorkflowClient.start(stubF::execute);

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

    io.temporal.common.SearchAttributes decoded =
        SearchAttributesUtil.decodeTyped(searchAttrFromEvent);
    assertEquals(searchAttributes, decoded);
  }

  @Test
  public void testInvalidSearchAttributeKey() {
    io.temporal.common.SearchAttributes searchAttributes =
        io.temporal.common.SearchAttributes.newBuilder(DEFAULT_SEARCH_ATTRIBUTES)
            .set(TEST_UNKNOWN_KEY, "some unknown value")
            .build();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTypedSearchAttributes(searchAttributes)
            .build();
    TestSignaledWorkflow unregisteredKeyStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    try {
      WorkflowClient.start(unregisteredKeyStub::execute);
      fail();
    } catch (WorkflowServiceException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
      assertEquals(Status.Code.INVALID_ARGUMENT, sre.getStatus().getCode());
    }
  }

  @Test
  public void testSearchAttributesPresentInChildWorkflow() {
    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    client.execute();
  }

  public static class TestWorkflowImpl implements TestSignaledWorkflow {
    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void signal(String arg) {}
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
          ChildWorkflowOptions.newBuilder()
              .setTypedSearchAttributes(DEFAULT_SEARCH_ATTRIBUTES)
              .build();
      TestChildWorkflow child = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      child.execute();
    }
  }

  public static class TestChild implements TestChildWorkflow {
    @Override
    public void execute() {
      // Check that search attributes are inherited by child workflows.
      assertEquals(DEFAULT_SEARCH_ATTRIBUTES, Workflow.getTypedSearchAttributes());
    }
  }
}
