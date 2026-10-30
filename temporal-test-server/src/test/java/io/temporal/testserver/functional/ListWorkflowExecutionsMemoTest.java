package io.temporal.testserver.functional;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ListWorkflowExecutionsMemoTest {
  private static final DataConverter DATA_CONVERTER = DefaultDataConverter.newDefaultInstance();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(UpsertMemoWorkflowImpl.class).build();

  @Test
  public void listedExecutionsIncludeMemo() {
    Map<String, Object> startMemo = new HashMap<>();
    startMemo.put("startKey", "startValue");
    startMemo.put("removedKey", "removedValue");
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId("list-memo-" + UUID.randomUUID())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(startMemo)
            .build();
    UpsertMemoWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(UpsertMemoWorkflow.class, options);
    WorkflowClient.start(workflow::execute);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    WorkflowExecution execution = stub.getExecution();

    Map<String, Payload> expectedMemo = new HashMap<>();
    expectedMemo.put("startKey", DATA_CONVERTER.toPayload("startValue").get());
    expectedMemo.put("upsertedKey", DATA_CONVERTER.toPayload("upsertedValue").get());

    awaitMemoUpserted(execution);
    WorkflowExecutionInfo open = findOpen(execution);
    Assert.assertEquals(expectedMemo, open.getMemo().getFieldsMap());

    workflow.finish();
    stub.getResult(Void.class);
    WorkflowExecutionInfo closed = findClosed(execution);
    Assert.assertEquals(expectedMemo, closed.getMemo().getFieldsMap());
  }

  private void awaitMemoUpserted(WorkflowExecution execution) {
    DescribeWorkflowExecutionRequest request =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setExecution(execution)
            .build();
    for (int i = 0; i < 50; i++) {
      Map<String, Payload> memo =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .describeWorkflowExecution(request)
              .getWorkflowExecutionInfo()
              .getMemo()
              .getFieldsMap();
      if (memo.containsKey("upsertedKey")) {
        return;
      }
      testWorkflowRule.getTestEnvironment().sleep(Duration.ofMillis(100));
    }
    throw new AssertionError("Memo was not upserted in time");
  }

  private WorkflowExecutionInfo findOpen(WorkflowExecution execution) {
    List<WorkflowExecutionInfo> executions =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .listOpenWorkflowExecutions(
                ListOpenWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build())
            .getExecutionsList();
    return findExecution(executions, execution);
  }

  private WorkflowExecutionInfo findClosed(WorkflowExecution execution) {
    List<WorkflowExecutionInfo> executions =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .listClosedWorkflowExecutions(
                ListClosedWorkflowExecutionsRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build())
            .getExecutionsList();
    return findExecution(executions, execution);
  }

  private static WorkflowExecutionInfo findExecution(
      List<WorkflowExecutionInfo> executions, WorkflowExecution execution) {
    return executions.stream()
        .filter(info -> info.getExecution().getRunId().equals(execution.getRunId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Execution not listed: " + execution));
  }

  @WorkflowInterface
  public interface UpsertMemoWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void finish();
  }

  public static class UpsertMemoWorkflowImpl implements UpsertMemoWorkflow {
    private boolean finished;

    @Override
    public void execute() {
      Workflow.upsertMemo(Collections.singletonMap("upsertedKey", "upsertedValue"));
      Workflow.upsertMemo(Collections.singletonMap("removedKey", null));
      Workflow.await(() -> finished);
    }

    @Override
    public void finish() {
      finished = true;
    }
  }
}
