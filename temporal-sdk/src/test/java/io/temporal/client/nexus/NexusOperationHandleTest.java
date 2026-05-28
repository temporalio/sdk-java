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

  @Test
  public void cancelSucceedsForStartedOperation() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.cancel();
    assertCancellationRecorded(handle, /* expectedReason= */ null);
  }

  @Test
  public void cancelWithReasonSucceedsForStartedOperation() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.cancel("test-cancel-reason");
    assertCancellationRecorded(handle, "test-cancel-reason");
  }

  @Test
  public void cancelWithNullReasonSucceeds() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.cancel(null);
    assertCancellationRecorded(handle, /* expectedReason= */ null);
  }

  @Test
  public void terminateSucceedsForStartedOperation() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate();
    assertTerminatedFailure(handle);
  }

  @Test
  public void terminateWithReasonSucceedsForStartedOperation() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate("test-terminate-reason");
    assertTerminatedFailure(handle);
  }

  @Test
  public void terminateWithNullReasonSucceeds() {
    UntypedNexusOperationHandle handle = startPendingOperation();
    handle.terminate(null);
    assertTerminatedFailure(handle);
  }

  /**
   * Async operations stay in RUNNING state until something external transitions them — used by
   * cancel/terminate tests so the lifecycle RPC has a non-terminal operation to act on.
   */
  private UntypedNexusOperationHandle startPendingOperation() {
    return startOperation(TestNexusServiceImpl.ASYNC_PREFIX + UUID.randomUUID());
  }

  /**
   * Terminate is forceful and immediate per the proto contract; the server transitions the
   * operation to TERMINATED regardless of handler state, so {@code getResult} promptly throws
   * {@link NexusOperationFailedException}.
   */
  private static void assertTerminatedFailure(UntypedNexusOperationHandle handle) {
    try {
      handle.getResult(15, java.util.concurrent.TimeUnit.SECONDS, String.class);
      Assert.fail("expected getResult to throw after the operation was terminated");
    } catch (NexusOperationFailedException expected) {
      // The TerminatedFailure shows up either on this exception's message or via getCause().
    } catch (java.util.concurrent.TimeoutException e) {
      Assert.fail("getResult timed out — terminate should have produced a terminal outcome");
    }
  }

  /**
   * Cancel does NOT auto-transition the operation per the proto contract (handler cooperation is
   * required). Assert via {@code describe()} that the cancellation request was at least recorded
   * server-side.
   */
  private static void assertCancellationRecorded(
      UntypedNexusOperationHandle handle, @javax.annotation.Nullable String expectedReason) {
    NexusOperationExecutionDescription description = handle.describe();
    Assert.assertNotNull(
        "expected cancellation_info to be recorded after cancel()",
        description.getCancellationInfo());
    if (expectedReason != null) {
      Assert.assertEquals(expectedReason, description.getCancellationInfo().getReason());
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
            // transitions it.
            return OperationStartResult.async("token-" + UUID.randomUUID());
          }
          return OperationStartResult.sync("echo:" + (input == null ? "<null>" : input));
        }

        @Override
        public void cancel(OperationContext context, OperationCancelDetails details) {
          // No-op. Tests assert cancellation visibility via describe()'s CancellationInfo
          // rather than driving the operation to a terminal state from the handler.
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
