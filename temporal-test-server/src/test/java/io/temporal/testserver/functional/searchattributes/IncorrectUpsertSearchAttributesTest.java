package io.temporal.testserver.functional.searchattributes;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableMap;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.client.WorkflowOptions;
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
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(WORKFLOW_ID)
            .build();

    TestWorkflows.PrimitiveWorkflow stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    stubF.execute();

    History history = testWorkflowRule.getExecutionHistory(WORKFLOW_ID).getHistory();
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
  }

  @Test
  public void searchAttributeIsIncorrectValueType() {
    final String WORKFLOW_ID = "workflow-with-sa-incorrect-value-type-" + new Random().nextInt();
    SEARCH_ATTRIBUTES = ImmutableMap.of(DEFAULT_KEY_INTEGER, "this_is_string_and_not_an_int");

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(WORKFLOW_ID)
            .build();

    TestWorkflows.PrimitiveWorkflow stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    stubF.execute();

    History history = testWorkflowRule.getExecutionHistory(WORKFLOW_ID).getHistory();
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
  }

  public static class UpsertingWorkflow implements TestWorkflows.PrimitiveWorkflow {
    @SuppressWarnings("deprecation")
    @Override
    public void execute() {
      // try to set incorrect search attributes on the first attempt
      if (FIRST_WFT_ATTEMPT.getAndSet(false)) {
        Workflow.upsertSearchAttributes(SEARCH_ATTRIBUTES);
      }
    }
  }
}
