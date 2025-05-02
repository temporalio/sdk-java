package io.temporal.workflow.updateTest;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.TestMultiArgWorkflowUpdateFunctions;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TypedUpdateTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(
              TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflowImpl.class)
          .build();

  @Test
  public void testTypedStubSync() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    Assert.assertEquals("func", workflow.func());
    Assert.assertEquals("input", workflow.func1("input"));
    Assert.assertEquals("input2", workflow.func2("input", 2));
    Assert.assertEquals("input23", workflow.func3("input", 2, 3));
    Assert.assertEquals("input234", workflow.func4("input", 2, 3, 4));
    Assert.assertEquals("input2345", workflow.func5("input", 2, 3, 4, 5));
    Assert.assertEquals("input23456", workflow.func6("input", 2, 3, 4, 5, 6));

    workflow.proc();
    workflow.proc1("input");
    workflow.proc2("input", 2);
    workflow.proc3("input", 2, 3);
    workflow.proc4("input", 2, 3, 4);
    workflow.proc5("input", 2, 3, 4, 5);
    workflow.proc6("input", 2, 3, 4, 5, 6);

    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("procinputinput2input23input234input2345input23456", result);
  }

  @Test
  public void testTypedAsync() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow workflow =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowUpdateFunctions.TestMultiArgUpdateWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    UpdateOptions<String> updateOptions =
        UpdateOptions.<String>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();

    Assert.assertEquals(
        "func", WorkflowClient.startUpdate(workflow::func, updateOptions).getResultAsync().get());
    Assert.assertEquals(
        "input",
        WorkflowClient.startUpdate(workflow::func1, "input", updateOptions).getResultAsync().get());
    Assert.assertEquals(
        "input2",
        WorkflowClient.startUpdate(workflow::func2, "input", 2, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input23",
        WorkflowClient.startUpdate(workflow::func3, "input", 2, 3, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input234",
        WorkflowClient.startUpdate(workflow::func4, "input", 2, 3, 4, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input2345",
        WorkflowClient.startUpdate(workflow::func5, "input", 2, 3, 4, 5, updateOptions)
            .getResultAsync()
            .get());
    Assert.assertEquals(
        "input23456",
        WorkflowClient.startUpdate(workflow::func6, "input", 2, 3, 4, 5, 6, updateOptions)
            .getResultAsync()
            .get());

    UpdateOptions<Void> updateVoidOptions =
        UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();
    WorkflowClient.startUpdate(workflow::proc, updateVoidOptions).getResultAsync().get();
    WorkflowClient.startUpdate(workflow::proc1, "input", updateVoidOptions).getResultAsync().get();
    WorkflowClient.startUpdate(workflow::proc2, "input", 2, updateVoidOptions)
        .getResultAsync()
        .get();
    WorkflowClient.startUpdate(workflow::proc3, "input", 2, 3, updateVoidOptions)
        .getResultAsync()
        .get();
    WorkflowClient.startUpdate(workflow::proc4, "input", 2, 3, 4, updateVoidOptions)
        .getResultAsync()
        .get();
    WorkflowClient.startUpdate(workflow::proc5, "input", 2, 3, 4, 5, updateVoidOptions)
        .getResultAsync()
        .get();
    WorkflowClient.startUpdate(workflow::proc6, "input", 2, 3, 4, 5, 6, updateVoidOptions)
        .getResultAsync()
        .get();

    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("procinputinput2input23input234input2345input23456", result);
  }
}
