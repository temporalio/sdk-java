package io.temporal.workflow;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.GsonJsonPayloadConverter;
import io.temporal.internal.client.WorkflowClientHelper;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class MemoTest {

  private static final String MEMO_KEY = "testKey";
  private static final String MEMO_VALUE = "testValue";
  private static final String MEMO_KEY_2 = "testKey2";
  private static final Integer MEMO_VALUE_2 = 1;
  private static final String MEMO_KEY_MISSING = "testKey3";
  private static final Map<String, Object> MEMO =
      ImmutableMap.of(
          MEMO_KEY, MEMO_VALUE, MEMO_KEY_2, Collections.singletonMap(MEMO_KEY_2, MEMO_VALUE_2));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(WorkflowWithMemoImpl.class).build();

  @Rule
  public SDKTestWorkflowRule testNoMemoWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(WorkflowWithoutMemoImpl.class).build();

  @Test
  public void testMemo() {
    if (testWorkflowRule.getTestEnvironment() == null) {
      return;
    }

    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(MEMO)
            .build();

    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestNoArgsWorkflowFunc.class, workflowOptions);
    WorkflowExecution executionF = WorkflowClient.start(stubF::func);

    GetWorkflowExecutionHistoryResponse historyResp =
        WorkflowClientHelper.getHistoryPage(
            testWorkflowRule.getWorkflowServiceStubs(),
            SDKTestWorkflowRule.NAMESPACE,
            executionF,
            ByteString.EMPTY,
            new NoopScope());
    HistoryEvent startEvent = historyResp.getHistory().getEvents(0);
    Memo memoFromEvent = startEvent.getWorkflowExecutionStartedEventAttributes().getMemo();
    Payload memoBytes = memoFromEvent.getFieldsMap().get(MEMO_KEY);
    String memoRetrieved =
        GsonJsonPayloadConverter.getInstance().fromData(memoBytes, String.class, String.class);
    assertEquals(MEMO_VALUE, memoRetrieved);
  }

  @Test
  public void testMemoInWorkflow() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(MEMO)
            .build();

    NoArgsWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(NoArgsWorkflow.class, workflowOptions);
    workflow.execute();
  }

  @Test
  public void testNoMemoInWorkflowFailsGetMemoGracefully() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testNoMemoWorkflowRule.getTaskQueue())
            .toBuilder()
            .setTaskQueue(testNoMemoWorkflowRule.getTaskQueue())
            .build();

    NoArgsWorkflow workflow =
        testNoMemoWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(NoArgsWorkflow.class, workflowOptions);
    workflow.execute();
  }

  public static class WorkflowWithMemoImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      // Simple value found
      assertEquals(MEMO_VALUE, Workflow.getMemo(MEMO_KEY, String.class));

      // Map value found
      Map result =
          Workflow.getMemo(
              MEMO_KEY_2, Map.class, new TypeToken<HashMap<String, Integer>>() {}.getType());
      assertTrue(result instanceof HashMap);
      assertEquals(MEMO_VALUE_2, result.get(MEMO_KEY_2));

      // Requested mismatched type
      boolean throwsDataConverterException = false;
      try {
        Workflow.getMemo(MEMO_KEY, Integer.class);
      } catch (DataConverterException e) {
        throwsDataConverterException = true;
      } finally {
        assertTrue(throwsDataConverterException);
      }

      // Missing key not found
      assertNull(Workflow.getMemo(MEMO_KEY_MISSING, String.class));
    }
  }

  public static class WorkflowWithoutMemoImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      // Missing key not found
      assertNull(Workflow.getMemo(MEMO_KEY, String.class));
    }
  }
}
