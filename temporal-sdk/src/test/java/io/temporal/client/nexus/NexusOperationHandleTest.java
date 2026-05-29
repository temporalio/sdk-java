package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationException;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link UntypedNexusOperationHandle} per-execution lifecycle methods returned by {@link
 * NexusClient#getHandle(String)}: {@code describe()}, {@code cancel()}/{@code cancel(reason)}, and
 * {@code terminate()}/{@code terminate(reason)}.
 */
public class NexusOperationHandleTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireExternalService() {
    // The time-skipping test server does not implement standalone Nexus operation RPCs.
    assumeTrue(
        "standalone Nexus operations require a real server",
        SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void describeReturnsDescriptionForStartedOperation() {
    UntypedNexusOperationHandle handle = startOperation();

    NexusOperationExecutionDescription description = handle.describe();

    Assert.assertNotNull(description);
    Assert.assertNotNull(description.getRunId());
    Assert.assertEquals(handle.getNexusOperationRunId(), description.getRunId());
    Assert.assertNotNull(description.getRawResponse());
  }

  @Test
  public void describeWithoutRunIdTargetsLatest() {
    UntypedNexusOperationHandle started = startOperation();
    // Re-bind a handle with no pinned run ID — server should resolve to the latest run.
    UntypedNexusOperationHandle handle =
        testWorkflowRule.getNexusClient().getHandle(started.getNexusOperationId());

    NexusOperationExecutionDescription description = handle.describe();

    Assert.assertNotNull(description);
    Assert.assertEquals(started.getNexusOperationRunId(), description.getRunId());
  }

  // The cancel call just requests the handler to cancel.
  // It doesn't automatically cancel. So we are testing not that it
  // cancelled the operation, but checking the number of cancel
  // invokations the test server received to make sure it increments.
  @Test
  public void cancelSucceedsForStartedOperation() {
    int before = EchoNexusServiceImpl.cancelInvocations.get();
    startPendingOperation().cancel();
    assertCancelDelivered(before);
  }

  @Test
  public void cancelWithReasonSucceedsForStartedOperation() {
    int before = EchoNexusServiceImpl.cancelInvocations.get();
    startPendingOperation().cancel("test-cancel-reason");
    assertCancelDelivered(before);
  }

  @Test
  public void cancelWithNullReasonSucceeds() {
    int before = EchoNexusServiceImpl.cancelInvocations.get();
    startPendingOperation().cancel(null);
    assertCancelDelivered(before);
  }

  /**
   * Polls the handler's invocation counter to confirm the cancel RPC reached the worker and the
   * handler's {@code cancel(...)} callback ran (the dispatch is asynchronous — server schedules a
   * cancel task, worker polls it, then the callback fires).
   */
  private static void assertCancelDelivered(int countBeforeCancel) {
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (EchoNexusServiceImpl.cancelInvocations.get() <= countBeforeCancel
        && System.nanoTime() < deadlineNanos) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    Assert.assertTrue(
        "cancel RPC was not delivered to the handler within the poll budget",
        EchoNexusServiceImpl.cancelInvocations.get() > countBeforeCancel);
  }

  @Test
  public void terminateSucceedsForStartedOperation() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate();
    assertTerminalFailure(handle);
  }

  @Test
  public void terminateWithReasonSucceedsForStartedOperation() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate("test-terminate-reason");
    assertTerminalFailure(handle);
  }

  @Test
  public void terminateWithNullReasonSucceeds() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate(null);
    assertTerminalFailure(handle);
  }

  /**
   * Starts an operation whose handler returns an async-started result without ever completing, so
   * the lifecycle RPCs have a non-terminal operation to act on.
   */
  private UntypedNexusOperationHandle startPendingOperation() {
    return startOperation(EchoNexusServiceImpl.ASYNC_PREFIX + UUID.randomUUID());
  }

  /**
   * Terminate is forceful and immediate per the proto contract; the server transitions the
   * operation to TERMINATED regardless of handler state, so {@code getResult} promptly throws
   * {@link NexusOperationFailedException}.
   */
  private static void assertTerminalFailure(UntypedNexusOperationHandle handle) {
    try {
      handle.getResult(15, java.util.concurrent.TimeUnit.SECONDS, String.class);
      Assert.fail("expected getResult to throw after the operation was terminated");
    } catch (NexusOperationFailedException expected) {
      // The TerminatedFailure shows up either on this exception's message or via getCause().
    } catch (java.util.concurrent.TimeoutException e) {
      Assert.fail("getResult timed out — terminate should have produced a terminal outcome");
    }
  }

  @Test
  public void getResultReturnsTypedResultForSyncOperation() {
    String result = NexusOperationHandle.fromUntyped(startOperation(), String.class).getResult();

    Assert.assertNotNull(result);
    Assert.assertTrue("expected echo: prefix, got: " + result, result.startsWith("echo:ping-"));
  }

  @Test
  public void getResultUntypedReturnsResultForSyncOperation() {
    String result = startOperation().getResult(String.class);

    Assert.assertNotNull(result);
    Assert.assertTrue(result.startsWith("echo:ping-"));
  }

  @Test
  public void getResultAsyncReturnsTypedResultForSyncOperation() throws Exception {
    String result =
        NexusOperationHandle.fromUntyped(startOperation(), String.class)
            .getResultAsync()
            .get(60, java.util.concurrent.TimeUnit.SECONDS);

    Assert.assertNotNull(result);
    Assert.assertTrue(result.startsWith("echo:ping-"));
  }

  private UntypedNexusOperationHandle startOperation() {
    return startOperation(null);
  }

  private UntypedNexusOperationHandle startOperation(
      @javax.annotation.Nullable String inputOverride) {
    NexusClient client = testWorkflowRule.getNexusClient();
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    String inputValue =
        inputOverride != null ? inputOverride : "ping-handle-test-" + UUID.randomUUID();

    UntypedNexusServiceClient svcClient =
        client.newUntypedNexusServiceClient(
            endpoint.getSpec().getName(),
            TestNexusServices.TestNexusService1.class.getSimpleName());
    StartNexusOperationOptions opts =
        StartNexusOperationOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();
    UntypedNexusOperationHandle handle = svcClient.start("operation", opts, inputValue);

    Assert.assertNotNull("expected start to return a run ID", handle.getNexusOperationRunId());
    return handle;
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  @Test
  public void getResultPropagatesOperationFailure() {
    UntypedNexusOperationHandle handle = startOperation(EchoNexusServiceImpl.FAIL_PREFIX + "boom");
    String operationId = handle.getNexusOperationId();

    try {
      handle.getResult(String.class);
      Assert.fail("expected getResult to throw because the operation handler failed");
    } catch (NexusOperationException e) {
      // Outer: NexusOperationFailedException carrying the failed operation's ID.
      Assert.assertTrue(
          "expected NexusOperationFailedException, got " + e.getClass().getSimpleName(),
          e instanceof NexusOperationFailedException);
      Assert.assertEquals(operationId, e.getOperationId());
      Assert.assertTrue(
          "expected outer message to reference the operation ID, got: " + e.getMessage(),
          e.getMessage() != null && e.getMessage().contains(operationId));

      // The full cause chain: dataConverter.failureToException(...) translates the proto Failure
      // into a Java exception. Walk every link and verify the handler's reason surfaces somewhere.
      boolean foundHandlerFailure = false;
      for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
        if (c.getMessage() != null && c.getMessage().contains("intentional failure")) {
          foundHandlerFailure = true;
          break;
        }
        if (c.getCause() == c) {
          break;
        }
      }
      Assert.assertTrue(
          "expected cause chain to include the handler's failure message, got: "
              + collectMessages(e),
          foundHandlerFailure);
    }
  }

  private static String collectMessages(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append(c.getClass().getSimpleName()).append(":").append(c.getMessage()).append(" | ");
      if (c.getCause() == c) {
        break;
      }
    }
    return sb.toString();
  }
}
