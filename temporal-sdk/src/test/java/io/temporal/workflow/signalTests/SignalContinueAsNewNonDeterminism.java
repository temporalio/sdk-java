package io.temporal.workflow.signalTests;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.NonDeterministicException;
import io.temporal.worker.WorkflowImplementationOptions;
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

public class SignalContinueAsNewNonDeterminism {
  private static final Semaphore workflowTaskProcessed = new Semaphore(0);

  private static final CompletableFuture<Boolean> continueAsNew = new CompletableFuture<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(Throwable.class)
                  .build(),
              TestSignaledWorkflowImpl.class)
          .build();

  @Test
  public void testSignalContinueAsNewNonDeterminism()
      throws ExecutionException, InterruptedException {
    // Verify we report nondeterminism when a signal handler is nondeterministic and calls continue
    // as new on replay
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
      client.signal();
      workflowTaskProcessed.acquire();
    }
    continueAsNew.complete(true);

    // Force replay, expected to fail with NonDeterministicException
    testWorkflowRule.invalidateWorkflowCache();
    client.signal();
    WorkflowFailedException e =
        Assert.assertThrows(WorkflowFailedException.class, () -> client.execute(false));
    assertThat(e.getCause(), is(instanceOf(ApplicationFailure.class)));
    assertEquals(
        NonDeterministicException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
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
      // Intentionally introduce non determinism
      if (continueAsNew.getNow(false)) {
        Workflow.continueAsNew(true);
      }
      workflowTaskProcessed.release();
    }
  }
}
