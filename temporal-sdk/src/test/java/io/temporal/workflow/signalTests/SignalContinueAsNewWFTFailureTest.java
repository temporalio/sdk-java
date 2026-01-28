package io.temporal.workflow.signalTests;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalContinueAsNewWFTFailureTest {
  private static final Semaphore workflowTaskProcessed = new Semaphore(1);

  private static final CompletableFuture<Boolean> continueAsNew = new CompletableFuture<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestSignaledWorkflowImpl.class).build();

  @Test
  public void testSignalContinueAsNewAfterWFTFailure()
      throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .build();
    TestSignaledWorkflow client =
        workflowClient.newWorkflowStub(TestSignaledWorkflow.class, options);

    WorkflowClient.start(client::execute, false);
    for (int i = 0; i < 5; i++) {
      workflowTaskProcessed.acquire();
      client.signal();
    }
    continueAsNew.complete(true);

    Assert.assertEquals("finished", client.execute(false));
  }

  @WorkflowInterface
  public interface TestSignaledWorkflow {

    @WorkflowMethod
    String execute(boolean finish);

    @SignalMethod
    void signal() throws ExecutionException, InterruptedException;
  }

  public static class TestSignaledWorkflowImpl implements TestSignaledWorkflow {

    @Override
    public String execute(boolean finish) {
      Workflow.await(() -> finish);
      return "finished";
    }

    @Override
    public void signal() throws ExecutionException, InterruptedException {
      if (continueAsNew.getNow(false)) {
        Workflow.continueAsNew(true);
      }
      workflowTaskProcessed.release();
      throw new RuntimeException("fail workflow task");
    }
  }
}
