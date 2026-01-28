package io.temporal.workflow.searchattributes;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;

/** Typed attribute translation of {@link UpsertSearchAttributeTest} */
public class UpsertTypedSearchAttributeTest {

  private static final String TEST_VALUE = "test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestUpsertSearchAttributesImpl.class)
          .registerSearchAttribute(TestUpsertSearchAttributesImpl.MY_KEYWORD_LIST_ATTR)
          .build();

  @Test
  public void testUpsertSearchAttributes() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTypedSearchAttributes(
                SearchAttributes.newBuilder()
                    .set(SearchAttributeKey.forText("CustomTextField"), "custom")
                    .build())
            .build();
    TestWorkflowStringArg testWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflowStringArg.class, workflowOptions);
    WorkflowExecution execution =
        WorkflowClient.start(testWorkflow::execute, testWorkflowRule.getTaskQueue());
    testWorkflow.execute(testWorkflowRule.getTaskQueue());
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "upsertTypedSearchAttributes",
            "sleep PT0.1S",
            "upsertTypedSearchAttributes",
            "sleep PT0.1S",
            "upsertTypedSearchAttributes",
            "upsertTypedSearchAttributes",
            "sleep PT0.1S",
            "upsertTypedSearchAttributes",
            "upsertTypedSearchAttributes",
            "upsertTypedSearchAttributes");
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES);
  }

  public static class TestUpsertSearchAttributesImpl implements TestWorkflowStringArg {

    public static final SearchAttributeKey<String> CUSTOM_KEYWORD_ATTR =
        SearchAttributeKey.forKeyword("CustomKeywordField");
    private static final SearchAttributeKey<List<String>> MY_KEYWORD_LIST_ATTR =
        SearchAttributeKey.forKeywordList("MyKeywordListField");

    private static final AtomicBoolean FAILED = new AtomicBoolean();

    @Override
    public void execute(String taskQueue) {
      SearchAttributes oldAttributes = Workflow.getTypedSearchAttributes();
      assertEquals(1, oldAttributes.size());

      Workflow.upsertTypedSearchAttributes(CUSTOM_KEYWORD_ATTR.valueSet(TEST_VALUE));
      assertEquals(TEST_VALUE, Workflow.getTypedSearchAttributes().get(CUSTOM_KEYWORD_ATTR));
      SearchAttributes newAttributes = Workflow.getTypedSearchAttributes();
      assertEquals(2, newAttributes.size());
      // triggering the end of the workflow task
      Workflow.sleep(100);

      Workflow.upsertTypedSearchAttributes(CUSTOM_KEYWORD_ATTR.valueUnset());
      assertFalse(Workflow.getTypedSearchAttributes().containsKey(CUSTOM_KEYWORD_ATTR));
      newAttributes = Workflow.getTypedSearchAttributes();
      assertEquals(1, newAttributes.size());
      // triggering the end of the workflow task
      Workflow.sleep(100);

      // two upserts in one WFT works fine
      Workflow.upsertTypedSearchAttributes(CUSTOM_KEYWORD_ATTR.valueSet("will be unset below"));
      Workflow.upsertTypedSearchAttributes(CUSTOM_KEYWORD_ATTR.valueUnset());
      Workflow.sleep(100);
      assertEquals(newAttributes, Workflow.getTypedSearchAttributes());

      // This helps with replaying the history one more time to check
      // against a possible NonDeterministicWorkflowError which could be caused by missing
      // UpsertWorkflowSearchAttributes event in history.
      if (FAILED.compareAndSet(false, true)) {
        throw new IllegalStateException("force replay");
      }

      // Also check keyword list
      Workflow.upsertTypedSearchAttributes(
          MY_KEYWORD_LIST_ATTR.valueSet(Arrays.asList("foo", "bar")));
      newAttributes = Workflow.getTypedSearchAttributes();
      assertEquals(2, newAttributes.size());
      assertEquals(
          Arrays.asList("foo", "bar"),
          Workflow.getTypedSearchAttributes().get(MY_KEYWORD_LIST_ATTR));

      // Change and confirm completely replaced
      Workflow.upsertTypedSearchAttributes(
          MY_KEYWORD_LIST_ATTR.valueSet(Arrays.asList("baz", "qux")));
      assertEquals(
          Arrays.asList("baz", "qux"),
          Workflow.getTypedSearchAttributes().get(MY_KEYWORD_LIST_ATTR));

      // Unset
      Workflow.upsertTypedSearchAttributes(MY_KEYWORD_LIST_ATTR.valueUnset());
      newAttributes = Workflow.getTypedSearchAttributes();
      assertEquals(1, newAttributes.size());
    }
  }
}
