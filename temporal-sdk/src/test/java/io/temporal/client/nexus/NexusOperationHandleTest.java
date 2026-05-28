package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.OperationStartDetails;
import io.nexusrpc.handler.OperationStartResult;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
          .setNexusServiceImplementation(new TestNexusServiceImpl())
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
    int before = TestNexusServiceImpl.cancelInvocations.get();
    startPendingOperation().cancel();
    assertCancelDelivered(before);
  }

  @Test
  public void cancelWithReasonSucceedsForStartedOperation() {
    int before = TestNexusServiceImpl.cancelInvocations.get();
    startPendingOperation().cancel("test-cancel-reason");
    assertCancelDelivered(before);
  }

  @Test
  public void cancelWithNullReasonSucceeds() {
    int before = TestNexusServiceImpl.cancelInvocations.get();
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
    while (TestNexusServiceImpl.cancelInvocations.get() <= countBeforeCancel
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
        TestNexusServiceImpl.cancelInvocations.get() > countBeforeCancel);
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
    return startOperation(TestNexusServiceImpl.ASYNC_PREFIX + UUID.randomUUID());
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

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    /** Inputs starting with this prefix make the handler throw, exercising the failure path. */
    static final String FAIL_PREFIX = "FAIL:";

    /**
     * Inputs starting with this prefix make the handler return an async-started result without ever
     * completing the operation. Used by cancel/terminate tests so the operation stays in RUNNING
     * state long enough for the lifecycle RPC to be observed.
     */
    static final String ASYNC_PREFIX = "ASYNC:";

    /**
     * Incremented every time the worker invokes the handler's {@code cancel(...)} callback. The
     * cancel tests poll this counter to verify the cancel RPC was delivered end-to-end (client →
     * server → worker), even though the no-op cancel doesn't drive the operation to a terminal
     * state.
     */
    static final AtomicInteger cancelInvocations = new AtomicInteger();

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return new OperationHandler<String, String>() {
        @Override
        public OperationStartResult<String> start(
            OperationContext context, OperationStartDetails details, String input)
            throws OperationException {
          if (input != null && input.startsWith(FAIL_PREFIX)) {
            // OperationException.failed = definitive failure (no retries) so the caller's
            // getResult surfaces the failure instead of timing out.
            throw OperationException.failed("intentional failure: " + input);
          }
          if (input != null && input.startsWith(ASYNC_PREFIX)) {
            // Async-started: server keeps the operation in RUNNING state until something
            // external (terminate, cancellation that takes effect, or schedule-to-close)
            // transitions it. Terminate is server-forced so it transitions reliably; cancel is
            // cooperative and won't transition without a backing entity.
            return OperationStartResult.async("token-" + UUID.randomUUID());
          }
          return OperationStartResult.sync("echo:" + (input == null ? "<null>" : input));
        }

        @Override
        public void cancel(OperationContext context, OperationCancelDetails details) {
          // Record delivery for the cancel tests; otherwise a no-op. Driving the operation to a
          // terminal CANCELED state would require a backing entity (e.g. a workflow).
          cancelInvocations.incrementAndGet();
        }
      };
    }
  }

  @Test
  public void getResultPropagatesOperationFailure() {
    UntypedNexusOperationHandle handle = startOperation(TestNexusServiceImpl.FAIL_PREFIX + "boom");

    try {
      handle.getResult(String.class);
      Assert.fail("expected getResult to throw because the operation handler failed");
    } catch (RuntimeException e) {
      // The DataConverter wraps the proto Failure into a Java exception. Either the message
      // carries the handler's reason, or one of the cause links does.
      String combined = collectMessages(e);
      Assert.assertTrue(
          "expected exception chain to mention the handler failure, got: " + combined,
          combined.contains("intentional failure"));
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
