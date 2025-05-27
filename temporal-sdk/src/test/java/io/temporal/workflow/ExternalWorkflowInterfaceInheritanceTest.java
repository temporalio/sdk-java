package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ExternalWorkflowInterfaceInheritanceTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TargetWorkflowImpl.class, SignalerWorkflowImpl.class)
          .build();

  @Test
  public void testSignalWithParentInterface() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TargetWorkflow target =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TargetWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(target::execute);

    SignalerWorkflow signaler =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SignalerWorkflow.class);
    signaler.execute(execution.getWorkflowId());

    String result = WorkflowStub.fromTyped(target).getResult(String.class);
    assertEquals("retried", result);
  }

  public interface Retryable {
    @SignalMethod
    void retryNow();
  }

  @WorkflowInterface
  public interface TargetWorkflow extends Retryable {
    @WorkflowMethod
    String execute();
  }

  public static class TargetWorkflowImpl implements TargetWorkflow {
    private String status = "started";

    @Override
    public String execute() {
      Workflow.await(() -> status.equals("retried"));
      return status;
    }

    @Override
    public void retryNow() {
      status = "retried";
    }
  }

  @WorkflowInterface
  public interface SignalerWorkflow {
    @WorkflowMethod
    void execute(String workflowId);
  }

  public static class SignalerWorkflowImpl implements SignalerWorkflow {
    @Override
    public void execute(String workflowId) {
      Retryable stub = Workflow.newExternalWorkflowStub(Retryable.class, workflowId);
      stub.retryNow();
    }
  }
}
