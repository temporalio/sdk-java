package io.temporal.testserver.functional;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class FirstExecutionRunIdSupportTest {
  private static final String BAD_RUN_ID = UUID.randomUUID().toString();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WaitingWorkflow.class, ContinueAsNewWorkflowImpl.class)
          .build();

  @Test
  public void requestCancelWorkflowExecutionUsesFirstExecutionRunId() {
    WorkflowHandle handle = startWorkflow("cancel-first-execution-" + UUID.randomUUID());

    StatusRuntimeException sre =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                requestCancelWorkflowExecution(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId(handle.execution.getWorkflowId())
                        .setRunId(BAD_RUN_ID)
                        .build(),
                    BAD_RUN_ID));
    Assert.assertEquals(
        "Cancel with bad runID should fail", Status.NOT_FOUND.getCode(), sre.getStatus().getCode());

    sre =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                requestCancelWorkflowExecution(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId(handle.execution.getWorkflowId())
                        .build(),
                    BAD_RUN_ID));
    Assert.assertEquals(
        "Cancel with bad firstExecutionRunId should fail",
        Status.NOT_FOUND.getCode(),
        sre.getStatus().getCode());

    requestCancelWorkflowExecution(
        WorkflowExecution.newBuilder().setWorkflowId(handle.execution.getWorkflowId()).build(),
        handle.execution.getRunId());
    WorkflowFailedException failure =
        Assert.assertThrows(WorkflowFailedException.class, () -> handle.stub.getResult(Void.class));
    Assert.assertTrue(failure.getCause() instanceof CanceledFailure);
  }

  @Test
  public void terminateWorkflowExecutionUsesFirstExecutionRunId() {
    WorkflowHandle handle = startWorkflow("terminate-first-execution-" + UUID.randomUUID());

    StatusRuntimeException sre =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                terminateWorkflowExecution(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId(handle.execution.getWorkflowId())
                        .setRunId(BAD_RUN_ID)
                        .build(),
                    null));
    Assert.assertEquals(
        "Terminate with bad runID should fail",
        Status.NOT_FOUND.getCode(),
        sre.getStatus().getCode());

    sre =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                terminateWorkflowExecution(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId(handle.execution.getWorkflowId())
                        .build(),
                    BAD_RUN_ID));
    Assert.assertEquals(
        "Terminate with bad firstExecutionRunId should fail",
        Status.NOT_FOUND.getCode(),
        sre.getStatus().getCode());

    terminateWorkflowExecution(
        WorkflowExecution.newBuilder().setWorkflowId(handle.execution.getWorkflowId()).build(),
        handle.execution.getRunId());

    WorkflowFailedException failure =
        Assert.assertThrows(WorkflowFailedException.class, () -> handle.stub.getResult(Void.class));
    Assert.assertTrue(failure.getCause() instanceof TerminatedFailure);
  }

  @Test
  public void requestCancelWorkflowExecutionUsesFirstExecutionRunIdAfterContinueAsNew() {
    ContinueAsNewWorkflowHandle handle =
        startContinueAsNewWorkflow("cancel-first-execution-after-continue-" + UUID.randomUUID());

    String continuedRunId = awaitContinuedRun(handle);
    Assert.assertNotEquals(handle.firstExecution.getRunId(), continuedRunId);

    StatusRuntimeException sre =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                requestCancelWorkflowExecution(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId(handle.firstExecution.getWorkflowId())
                        .build(),
                    continuedRunId));
    // Using continued runId as firstExecutionRunId should fail
    Assert.assertEquals(
        "Cancel with wrong firstExecutionRunId should fail",
        Status.NOT_FOUND.getCode(),
        sre.getStatus().getCode());

    requestCancelWorkflowExecution(
        WorkflowExecution.newBuilder()
            .setWorkflowId(handle.firstExecution.getWorkflowId())
            .setRunId(handle.firstExecution.getRunId())
            .build(),
        handle.firstExecution.getRunId());

    WorkflowFailedException failure =
        Assert.assertThrows(WorkflowFailedException.class, () -> handle.stub.getResult(Void.class));
    Assert.assertTrue(failure.getCause() instanceof CanceledFailure);
  }

  @Test
  public void terminateWorkflowExecutionUsesFirstExecutionRunIdAfterContinueAsNew() {
    ContinueAsNewWorkflowHandle handle =
        startContinueAsNewWorkflow("cancel-first-execution-after-continue-" + UUID.randomUUID());

    String continuedRunId = awaitContinuedRun(handle);
    Assert.assertNotEquals(handle.firstExecution.getRunId(), continuedRunId);

    StatusRuntimeException sre =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                terminateWorkflowExecution(
                    WorkflowExecution.newBuilder()
                        .setWorkflowId(handle.firstExecution.getWorkflowId())
                        .build(),
                    continuedRunId));
    // Using continued runId as firstExecutionRunId should fail
    Assert.assertEquals(
        "Terminate with wrong firstExecutionRunId should fail",
        Status.NOT_FOUND.getCode(),
        sre.getStatus().getCode());

    terminateWorkflowExecution(
        WorkflowExecution.newBuilder()
            .setWorkflowId(handle.firstExecution.getWorkflowId())
            .setRunId(handle.firstExecution.getRunId())
            .build(),
        handle.firstExecution.getRunId());

    WorkflowFailedException failure =
        Assert.assertThrows(WorkflowFailedException.class, () -> handle.stub.getResult(Void.class));
    Assert.assertTrue(failure.getCause() instanceof TerminatedFailure);
  }

  private RequestCancelWorkflowExecutionResponse requestCancelWorkflowExecution(
      WorkflowExecution exec, @Nullable String firstRunId) {
    RequestCancelWorkflowExecutionRequest.Builder cancelBuilder =
        RequestCancelWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowExecution(exec)
            .setIdentity("test-client");
    if (firstRunId != null) {
      cancelBuilder.setFirstExecutionRunId(firstRunId);
    }
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .requestCancelWorkflowExecution(cancelBuilder.build());
  }

  private TerminateWorkflowExecutionResponse terminateWorkflowExecution(
      WorkflowExecution exec, @Nullable String firstRunId) {
    TerminateWorkflowExecutionRequest.Builder terminateBuilder =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowExecution(exec)
            .setIdentity("test-client");
    if (firstRunId != null) {
      terminateBuilder.setFirstExecutionRunId(firstRunId);
    }
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .terminateWorkflowExecution(terminateBuilder.build());
  }

  private WorkflowHandle startWorkflow(String workflowId) {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflows.PrimitiveWorkflow typedStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.PrimitiveWorkflow.class, options);
    WorkflowClient.start(typedStub::execute);

    WorkflowStub workflowStub = WorkflowStub.fromTyped(typedStub);
    return new WorkflowHandle(workflowStub, workflowStub.getExecution());
  }

  private static class WorkflowHandle {
    private final WorkflowStub stub;
    private final WorkflowExecution execution;

    private WorkflowHandle(WorkflowStub stub, WorkflowExecution execution) {
      this.stub = stub;
      this.execution = execution;
    }
  }

  private ContinueAsNewWorkflowHandle startContinueAsNewWorkflow(String workflowId) {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    ContinueAsNewWorkflow typedStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(ContinueAsNewWorkflow.class, options);
    WorkflowClient.start(() -> typedStub.execute(true));

    WorkflowStub workflowStub = WorkflowStub.fromTyped(typedStub);
    return new ContinueAsNewWorkflowHandle(workflowStub, workflowStub.getExecution());
  }

  private String awaitContinuedRun(ContinueAsNewWorkflowHandle handle) {
    DescribeWorkflowExecutionRequest request =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(handle.firstExecution.getWorkflowId())
                    .build())
            .build();

    for (int i = 0; i < 50; i++) {
      String currentRunId =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .describeWorkflowExecution(request)
              .getWorkflowExecutionInfo()
              .getExecution()
              .getRunId();
      if (!currentRunId.isEmpty() && !currentRunId.equals(handle.firstExecution.getRunId())) {
        return currentRunId;
      }
      testWorkflowRule.getTestEnvironment().sleep(Duration.ofMillis(100));
    }
    throw new AssertionError("Workflow did not continue as new in time");
  }

  private static class ContinueAsNewWorkflowHandle {
    private final WorkflowStub stub;
    private final WorkflowExecution firstExecution;

    private ContinueAsNewWorkflowHandle(WorkflowStub stub, WorkflowExecution firstExecution) {
      this.stub = stub;
      this.firstExecution = firstExecution;
    }
  }

  public static class WaitingWorkflow implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {
      Workflow.await(() -> false);
    }
  }

  @WorkflowInterface
  public interface ContinueAsNewWorkflow {
    @WorkflowMethod
    void execute(boolean continueAsNew);
  }

  public static class ContinueAsNewWorkflowImpl implements ContinueAsNewWorkflow {
    @Override
    public void execute(boolean continueAsNew) {
      if (continueAsNew) {
        Workflow.continueAsNew(false);
      }
      Workflow.await(() -> false);
    }
  }
}
