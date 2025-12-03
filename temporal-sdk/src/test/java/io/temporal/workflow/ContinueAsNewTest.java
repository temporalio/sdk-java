package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewTest {
  static final SearchAttributeKey<String> CUSTOM_KEYWORD_SA =
      SearchAttributeKey.forKeyword("CustomKeywordField");

  public static final int INITIAL_COUNT = 4;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestContinueAsNewImpl.class).build();

  @Test
  public void testContinueAsNew() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    options =
        WorkflowOptions.newBuilder(options)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build())
            .setTypedSearchAttributes(
                SearchAttributes.newBuilder().set(CUSTOM_KEYWORD_SA, "foo0").build())
            .build();
    TestContinueAsNew client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestContinueAsNew.class, options);
    int result = client.execute(INITIAL_COUNT, testWorkflowRule.getTaskQueue());
    Assert.assertEquals(111, result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method");
  }

  @WorkflowInterface
  public interface TestContinueAsNew {

    @WorkflowMethod
    int execute(int count, String continueAsNewTaskQueue);
  }

  public static class TestContinueAsNewImpl implements TestContinueAsNew {

    @Override
    public int execute(int count, String continueAsNewTaskQueue) {
      String taskQueue = Workflow.getInfo().getTaskQueue();
      if (count >= INITIAL_COUNT - 2) {
        assertEquals(10, Workflow.getInfo().getRetryOptions().getMaximumAttempts());
        assertEquals("foo0", Workflow.getTypedSearchAttributes().get(CUSTOM_KEYWORD_SA));
      } else {
        assertEquals(5, Workflow.getInfo().getRetryOptions().getMaximumAttempts());
        assertEquals("foo1", Workflow.getTypedSearchAttributes().get(CUSTOM_KEYWORD_SA));
      }
      if (count == 0) {
        assertEquals(continueAsNewTaskQueue, taskQueue);
        return 111;
      }
      Map<String, Object> memo = new HashMap<>();
      memo.put("myKey", "MyValue");
      RetryOptions retryOptions = null;
      SearchAttributes searchAttributes = null;
      // don't specify ContinueAsNewOptions on the first continue-as-new to test that RetryOptions
      // and SearchAttributes
      // are copied from the previous run.
      if (count == INITIAL_COUNT) {
        TestContinueAsNew next = Workflow.newContinueAsNewStub(TestContinueAsNew.class);
        next.execute(count - 1, continueAsNewTaskQueue);
        throw new RuntimeException("unreachable");
      }
      // don't specify RetryOptions and SearchAttributes on the second continue-as-new to test that
      // they are copied from
      // the previous run.
      if (count < INITIAL_COUNT - 1) {
        retryOptions = RetryOptions.newBuilder().setMaximumAttempts(5).build();
        searchAttributes = SearchAttributes.newBuilder().set(CUSTOM_KEYWORD_SA, "foo1").build();
      }
      ContinueAsNewOptions options =
          ContinueAsNewOptions.newBuilder()
              .setTaskQueue(continueAsNewTaskQueue)
              .setRetryOptions(retryOptions)
              .setMemo(memo)
              .setTypedSearchAttributes(searchAttributes)
              .build();
      TestContinueAsNew next = Workflow.newContinueAsNewStub(TestContinueAsNew.class, options);
      next.execute(count - 1, continueAsNewTaskQueue);
      throw new RuntimeException("unreachable");
    }
  }
}
