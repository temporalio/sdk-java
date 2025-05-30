package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalDuringLastWorkflowTaskTest {

  private static final AtomicInteger workflowTaskCount = new AtomicInteger();
  private static final CompletableFuture<Boolean> sendSignal = new CompletableFuture<>();
  private static final CompletableFuture<Boolean> assertCompleted = new CompletableFuture<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalDuringLastWorkflowTaskWorkflowImpl.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder().setDefaultDeadlockDetectionTimeout(5000).build())
          .build();

  @Test
  public void testSignalDuringLastWorkflowTask() throws ExecutionException, InterruptedException {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    TestSignaledWorkflow client = testWorkflowRule.newWorkflowStub(TestSignaledWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(client::execute);
    testWorkflowRule.registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          try {
            try {
              sendSignal.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(e);
            }
            client.signal("Signal Input");
          } catch (TimeoutException | ExecutionException e) {
            throw new RuntimeException(e);
          }
          Assert.assertEquals(
              "Signal Input",
              testWorkflowRule
                  .getWorkflowClient()
                  .newUntypedWorkflowStub(execution, Optional.empty())
                  .getResult(String.class));
          assertCompleted.complete(true);
        });
    testWorkflowRule.sleep(Duration.ofSeconds(2));
    assertTrue(assertCompleted.get());
  }

  public static class TestSignalDuringLastWorkflowTaskWorkflowImpl implements TestSignaledWorkflow {

    private String signal;

    @Override
    public String execute() {
      if (workflowTaskCount.incrementAndGet() == 1) {
        sendSignal.complete(true);
        // Never sleep in a real workflow using Thread.sleep.
        // Here it is to simulate a race condition.
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      return signal;
    }

    @Override
    public void signal(String arg) {
      signal = arg;
    }
  }
}
