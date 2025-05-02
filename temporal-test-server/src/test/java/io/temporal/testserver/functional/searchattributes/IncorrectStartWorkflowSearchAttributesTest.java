package io.temporal.testserver.functional.searchattributes;

import static org.hamcrest.CoreMatchers.instanceOf;
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

  @SuppressWarnings("deprecation")
  @Test
  public void searchAttributeIsNotRegistered() {
    final String WORKFLOW_ID = "workflow-with-non-existing-sa";
    Map<String, Object> searchAttributes = ImmutableMap.of(TEST_UNKNOWN_KEY, TEST_UNKNOWN_VALUE);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
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

  @SuppressWarnings("deprecation")
  @Test
  public void searchAttributeIsIncorrectValueType() {
    final String WORKFLOW_ID = "workflow-with-sa-incorrect-value-type";
    Map<String, Object> searchAttributes =
        ImmutableMap.of(DEFAULT_KEY_INTEGER, "this_is_string_and_not_an_int");

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
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
