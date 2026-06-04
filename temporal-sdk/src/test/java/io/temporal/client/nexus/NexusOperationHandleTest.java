package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.enums.v1.NexusOperationExecutionStatus;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationException;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusOperationNotFoundException;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Before;
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

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.supportsStandaloneNexusOperations());
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
  public void describeReturnsTerminalStateAfterSyncOperationCompletes() {
    // Drive a sync echo through to completion, then assert describe surfaces the terminal state.
    UntypedNexusOperationHandle handle = startOperation();
    String expected = handle.getResult(String.class);

    NexusOperationExecutionDescription description = handle.describe();

    Assert.assertEquals(
        NexusOperationExecutionStatus.NEXUS_OPERATION_EXECUTION_STATUS_COMPLETED,
        description.getStatus());
    Assert.assertNotNull("expected closeTime once terminal", description.getCloseTime());
    Assert.assertNotNull(
        "expected executionDuration once terminal", description.getExecutionDuration());
    // describe() defaults to includeOutcome=true, so the success payload should be present.
    Assert.assertTrue(
        "expected description.hasResult() after a successful sync operation",
        description.hasResult());
    Assert.assertEquals(expected, description.getResult(String.class).orElse(null));
    Assert.assertNull("expected no failure on a successful operation", description.getFailure());
  }

  @Test
  public void describeThrowsForUnknownOperationId() {
    // Mint an operation ID that the server has never seen; describe must surface the typed
    // NOT_FOUND-mapped exception rather than a raw gRPC status.
    String bogusOperationId = "does-not-exist-" + UUID.randomUUID();
    UntypedNexusOperationHandle handle =
        testWorkflowRule.getNexusClient().getHandle(bogusOperationId);

    try {
      handle.describe();
      Assert.fail("expected NexusOperationNotFoundException for an unknown operation ID");
    } catch (NexusOperationNotFoundException expected) {
      Assert.assertEquals(bogusOperationId, expected.getOperationId());
    }
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

  @Test
  public void getResultWithTimeoutFiresWhenOperationStaysPending() {
    // Start an async-pending operation that never completes on its own; the client-side
    // getResult(timeout, unit) overload must surface TimeoutException once the local budget
    // expires.
    UntypedNexusOperationHandle handle = startPendingOperation();
    try {
      handle.getResult(1, java.util.concurrent.TimeUnit.SECONDS, String.class);
      Assert.fail("expected TimeoutException when getResult's client-side budget expires");
    } catch (java.util.concurrent.TimeoutException expected) {
      // expected — terminate the operation so it doesn't outlive the test
      handle.terminate("cleanup-after-timeout-test");
    }
  }

  @Test
  public void getResultAsyncWithTimeoutFiresWhenOperationStaysPending() {
    // Mirror of the sync test for the CompletableFuture surface: the returned future must
    // complete exceptionally with TimeoutException once the supplied timeout expires.
    UntypedNexusOperationHandle handle = startPendingOperation();
    java.util.concurrent.CompletableFuture<String> future =
        handle.getResultAsync(1, java.util.concurrent.TimeUnit.SECONDS, String.class);
    try {
      future.get();
      Assert.fail("expected getResultAsync future to complete exceptionally with TimeoutException");
    } catch (java.util.concurrent.ExecutionException e) {
      Assert.assertTrue(
          "expected TimeoutException, got "
              + (e.getCause() == null ? "null" : e.getCause().getClass().getSimpleName()),
          e.getCause() instanceof java.util.concurrent.TimeoutException);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      handle.terminate("cleanup-after-timeout-test");
    }
  }

  /**
   * Polls the handler's invocation counter to confirm the cancel RPC reached the worker and the
   * handler's {@code cancel(...)} callback ran (the dispatch is asynchronous — server schedules a
   * cancel task, worker polls it, then the callback fires).
   *
   * <p>The 8-second budget sits just under the rule's default {@code DEFAULT_TEST_TIMEOUT_SECONDS =
   * 10}, so a missed delivery fails with the descriptive message below rather than the rule's
   * generic JUnit timeout.
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
  public void terminateTransitionsOperationToTerminatedStatus() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate("status-assertion");
    // assertTerminalFailure proves getResult observed terminality; describe must agree that the
    // server-side status was specifically TERMINATED (not CANCELED/FAILED/TIMED_OUT).
    assertTerminalFailure(handle);
    NexusOperationExecutionDescription description = handle.describe();
    Assert.assertEquals(
        NexusOperationExecutionStatus.NEXUS_OPERATION_EXECUTION_STATUS_TERMINATED,
        description.getStatus());
    Assert.assertNotNull(description.getCloseTime());
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
   * {@link NexusOperationFailedException}. Uses the no-timeout {@code getResult(Class)} overload;
   * the rule's global test timeout caps how long we wait.
   */
  private static void assertTerminalFailure(UntypedNexusOperationHandle handle) {
    try {
      handle.getResult(String.class);
      Assert.fail("expected getResult to throw after the operation was terminated");
    } catch (NexusOperationFailedException expected) {
      // The TerminatedFailure shows up either on this exception's message or via getCause().
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
            .setId(UUID.randomUUID().toString())
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

      // Cause: ApplicationFailure produced by NexusTaskHandlerImpl when it converts the
      // handler-thrown OperationException.failed(...) into a TemporalFailure
      // (ApplicationFailure.newFailureWithCause(message, "OperationError", null)). Lock down the
      // exact shape so any drift in that conversion surfaces here.
      Throwable cause = e.getCause();
      Assert.assertNotNull("expected NexusOperationFailedException to wrap a cause", cause);
      Assert.assertTrue(
          "expected cause to be ApplicationFailure, got " + cause.getClass().getSimpleName(),
          cause instanceof ApplicationFailure);
      ApplicationFailure appFailure = (ApplicationFailure) cause;
      Assert.assertEquals("OperationError", appFailure.getType());
      Assert.assertEquals("intentional failure: FAIL:boom", appFailure.getOriginalMessage());
      Assert.assertFalse(
          "OperationException.failed(...) currently translates to a retryable ApplicationFailure",
          appFailure.isNonRetryable());
      Assert.assertNull(
          "expected no further nested cause for a bare OperationException.failed(msg)",
          appFailure.getCause());
    }
  }
}
