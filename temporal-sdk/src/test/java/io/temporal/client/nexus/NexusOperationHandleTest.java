package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionDescription;
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
    startOperation().cancel();
    // No exception — server accepted the cancel request.
  }

  @Test
  public void cancelWithReasonSucceedsForStartedOperation() {
    startOperation().cancel("test-cancel-reason");
  }

  @Test
  public void cancelWithNullReasonSucceeds() {
    startOperation().cancel(null);
  }

  @Test
  public void terminateSucceedsForStartedOperation() {
    startOperation().terminate();
  }

  @Test
  public void terminateWithReasonSucceedsForStartedOperation() {
    startOperation().terminate("test-terminate-reason");
  }

  @Test
  public void terminateWithNullReasonSucceeds() {
    startOperation().terminate(null);
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

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (context, details, input) -> {
            if (input != null && input.startsWith(FAIL_PREFIX)) {
              // OperationException.failed = definitive failure (no retries) so the caller's
              // getResult surfaces the failure instead of timing out.
              throw OperationException.failed("intentional failure: " + input);
            }
            return "echo:" + (input == null ? "<null>" : input);
          });
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
