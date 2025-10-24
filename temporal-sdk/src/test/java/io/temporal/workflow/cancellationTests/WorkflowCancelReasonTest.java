package io.temporal.workflow.cancellationTests;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowCancelReasonTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(
              CancellationAwareWorkflow.class,
              CancelExternalWorkflowImpl.class,
              CascadingCancelWorkflowImpl.class)
          .build();

  @Test
  public void testCancellationReasonFromClient() {
    String workflowId = "client-cancel-" + UUID.randomUUID();
    CancellationReasonWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                CancellationReasonWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());

    WorkflowClient.start(workflow::execute);

    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    String reason = "client-cancel-reason";
    stub.cancel(reason);

    WorkflowFailedException exception =
        assertThrows(WorkflowFailedException.class, () -> stub.getResult(String.class));
    assertEquals(CanceledFailure.class, exception.getCause().getClass());

    assertEquals(expectedResult(reason), workflow.getRecordedReason());
  }

  @Test
  public void testCancellationReasonFromCommand() {
    String targetWorkflowId = "command-cancel-" + UUID.randomUUID();
    CancellationReasonWorkflow targetWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                CancellationReasonWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(targetWorkflowId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());

    WorkflowClient.start(targetWorkflow::execute);

    CancelExternalWorkflow canceller =
        testWorkflowRule.newWorkflowStub(CancelExternalWorkflow.class);
    String reason = "command-cancel-reason";
    WorkflowClient.start(canceller::execute, targetWorkflowId, reason);

    WorkflowStub targetStub = WorkflowStub.fromTyped(targetWorkflow);
    WorkflowFailedException exception =
        assertThrows(WorkflowFailedException.class, () -> targetStub.getResult(String.class));
    assertEquals(CanceledFailure.class, exception.getCause().getClass());

    assertEquals(expectedResult(reason), targetWorkflow.getRecordedReason());
  }

  @Test
  public void testCancellationReasonAbsent() {
    String workflowId = "client-cancel-null-" + UUID.randomUUID();
    CancellationReasonWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                CancellationReasonWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());

    WorkflowClient.start(workflow::execute);

    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.cancel();

    WorkflowFailedException exception =
        assertThrows(WorkflowFailedException.class, () -> stub.getResult(String.class));
    assertEquals(CanceledFailure.class, exception.getCause().getClass());

    assertEquals(expectedResult(""), workflow.getRecordedReason());
  }

  @Test
  public void testCancellationReasonDerivedFromContext() {
    String targetWorkflowId = "context-derived-" + UUID.randomUUID();
    CancellationReasonWorkflow targetWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                CancellationReasonWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(targetWorkflowId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());

    WorkflowClient.start(targetWorkflow::execute);

    CascadingCancelWorkflow cascaderWorkflow =
        testWorkflowRule.newWorkflowStub(CascadingCancelWorkflow.class);

    WorkflowClient.start(cascaderWorkflow::execute, targetWorkflowId);

    WorkflowStub cascaderStub = WorkflowStub.fromTyped(cascaderWorkflow);
    String reason = "context-derived-reason";
    cascaderStub.cancel(reason);

    WorkflowStub targetStub = WorkflowStub.fromTyped(targetWorkflow);
    WorkflowFailedException exception =
        assertThrows(WorkflowFailedException.class, () -> targetStub.getResult(String.class));
    assertEquals(CanceledFailure.class, exception.getCause().getClass());

    assertEquals(expectedResult(reason), targetWorkflow.getRecordedReason());
  }

  private static String expectedResult(String reason) {
    String part = String.valueOf(reason);
    return part + "|" + part;
  }

  @WorkflowInterface
  public interface CancellationReasonWorkflow {
    @WorkflowMethod
    String execute();

    @QueryMethod
    String getRecordedReason();
  }

  public static class CancellationAwareWorkflow implements CancellationReasonWorkflow {

    private String recordedReason;

    @Override
    public String execute() {
      Promise<String> cancellationRequest = CancellationScope.current().getCancellationRequest();
      String requestedReason = cancellationRequest.get();
      String scopeReason = CancellationScope.current().getCancellationReason();
      recordedReason = expectedResultFromWorkflow(requestedReason, scopeReason);
      System.out.println(recordedReason);
      return recordedReason;
    }

    @Override
    public String getRecordedReason() {
      return recordedReason;
    }

    private String expectedResultFromWorkflow(String requestedReason, String scopeReason) {
      return requestedReason + "|" + scopeReason;
    }
  }

  @WorkflowInterface
  public interface CancelExternalWorkflow {
    @WorkflowMethod
    void execute(String workflowId, String reason);
  }

  public static class CancelExternalWorkflowImpl implements CancelExternalWorkflow {

    @Override
    public void execute(String workflowId, String reason) {
      ExternalWorkflowStub externalWorkflow = Workflow.newUntypedExternalWorkflowStub(workflowId);
      externalWorkflow.cancel(reason);
    }
  }

  @WorkflowInterface
  public interface CascadingCancelWorkflow {
    @WorkflowMethod
    void execute(String workflowId);
  }

  public static class CascadingCancelWorkflowImpl implements CascadingCancelWorkflow {

    @Override
    public void execute(String workflowId) {
      ExternalWorkflowStub externalWorkflow = Workflow.newUntypedExternalWorkflowStub(workflowId);
      CancellationScope.current().getCancellationRequest().get();
      externalWorkflow.cancel();
    }
  }
}
