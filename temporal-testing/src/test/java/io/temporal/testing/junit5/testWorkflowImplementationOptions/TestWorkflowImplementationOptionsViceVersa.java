package io.temporal.testing.junit5.testWorkflowImplementationOptions;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.WorkflowInitialTime;
import io.temporal.testing.junit5.testWorkflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloWorkflowImpl;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowImplementationOptionsViceVersa {

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(HelloWorkflowImpl.class)
          .setInitialTime(Instant.parse("2021-10-10T10:01:00Z"))
          .build();

  @Test
  @WorkflowInitialTime("2020-01-01T01:00:00Z")
  public void extensionShouldNotBeAbleToCallActivityBasedOnMissingTimeouts(
      TestWorkflowEnvironment testEnv, WorkflowOptions workflowOptions, Worker worker)
      throws InterruptedException, ExecutionException, TimeoutException {

    WorkflowStub cut =
        testEnv.getWorkflowClient().newUntypedWorkflowStub("HelloWorkflow", workflowOptions);
    cut.start("World");

    CompletableFuture<String> resultAsync = cut.getResultAsync(String.class);
    assertThrows(TimeoutException.class, () -> resultAsync.get(5, TimeUnit.SECONDS));
  }
}
