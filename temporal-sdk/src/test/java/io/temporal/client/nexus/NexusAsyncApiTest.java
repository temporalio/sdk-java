package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Coverage tests for the {@link CompletableFuture}-returning surface on the standalone Nexus
 * client: {@link NexusServiceClient#executeAsync executeAsync} on the typed service client, plus
 * the {@code getResultAsync} overloads on both {@link NexusOperationHandle} and {@link
 * UntypedNexusOperationHandle}. Each overload is asserted against the existing sync echo handler so
 * the Java async API is exercised without depending on server-side async completion.
 */
public class NexusAsyncApiTest {

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

  // --- NexusServiceClient.executeAsync ---

  @Test
  public void serviceClientExecuteAsyncReturnsResult() throws Exception {
    String result =
        buildServiceClient()
            .executeAsync(
                TestNexusServices.TestNexusService1::operation, "hello", newOptionsWithId())
            .get();

    Assert.assertEquals("echo:hello", result);
  }

  @Test
  public void serviceClientExecuteAsyncWithOptionsReturnsResult() throws Exception {
    StartNexusOperationOptions options =
        StartNexusOperationOptions.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();

    String result =
        buildServiceClient()
            .executeAsync(TestNexusServices.TestNexusService1::operation, "world", options)
            .get();

    Assert.assertEquals("echo:world", result);
  }

  // --- NexusOperationHandle (typed) getResultAsync overloads ---

  @Test
  public void typedHandleGetResultAsyncReturnsResult() throws Exception {
    NexusOperationHandle<String> handle =
        buildServiceClient()
            .start(TestNexusServices.TestNexusService1::operation, "typed", newOptionsWithId());

    String result = handle.getResultAsync().get();

    Assert.assertEquals("echo:typed", result);
  }

  @Test
  public void typedHandleGetResultAsyncWithTimeoutReturnsResult() throws Exception {
    NexusOperationHandle<String> handle =
        buildServiceClient()
            .start(TestNexusServices.TestNexusService1::operation, "typed-tm", newOptionsWithId());

    // The 60s argument here exists to satisfy the API signature being exercised; the test rule's
    // global timeout will fail the test long before this value matters.
    String result = handle.getResultAsync(60, TimeUnit.SECONDS).get();

    Assert.assertEquals("echo:typed-tm", result);
  }

  // --- UntypedNexusOperationHandle getResultAsync overloads ---

  @Test
  public void untypedHandleGetResultAsyncByClassReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped");

    String result = handle.getResultAsync(String.class).get();

    Assert.assertEquals("echo:untyped", result);
  }

  @Test
  public void untypedHandleGetResultAsyncByClassAndTypeReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped-gen");

    String result = handle.getResultAsync(String.class, String.class).get();

    Assert.assertEquals("echo:untyped-gen", result);
  }

  @Test
  public void untypedHandleGetResultAsyncWithTimeoutByClassReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped-tm");

    String result = handle.getResultAsync(60, TimeUnit.SECONDS, String.class).get();

    Assert.assertEquals("echo:untyped-tm", result);
  }

  @Test
  public void untypedHandleGetResultAsyncWithTimeoutByClassAndTypeReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped-tm-gen");

    String result = handle.getResultAsync(60, TimeUnit.SECONDS, String.class, String.class).get();

    Assert.assertEquals("echo:untyped-tm-gen", result);
  }

  // --- Failure path ---

  @Test
  public void executeAsyncPropagatesOperationFailure() throws Exception {
    CompletableFuture<String> future =
        buildServiceClient()
            .executeAsync(
                TestNexusServices.TestNexusService1::operation,
                EchoNexusServiceImpl.FAIL_PREFIX + "boom",
                newOptionsWithId());

    try {
      future.get();
      Assert.fail("expected future to complete exceptionally");
    } catch (ExecutionException e) {
      // The JDK wraps the underlying exception in ExecutionException — that's expected. The SDK
      // MUST NOT introduce any further layer between this wrapper and the
      // NexusOperationFailedException; the chain below ExecutionException must be identical to the
      // synchronous getResult() path so CompletableFuture handling doesn't smuggle extra wrappers.
      Throwable cause = e.getCause();
      Assert.assertNotNull("expected ExecutionException to wrap a cause", cause);
      Assert.assertTrue(
          "expected NexusOperationFailedException directly under ExecutionException, got "
              + cause.getClass().getSimpleName(),
          cause instanceof NexusOperationFailedException);
      NexusOperationFailedException nexusFailure = (NexusOperationFailedException) cause;
      Assert.assertNotNull(nexusFailure.getOperationId());
      Assert.assertTrue(
          "expected outer message to reference the operation ID, got: " + nexusFailure.getMessage(),
          nexusFailure.getMessage() != null
              && nexusFailure.getMessage().contains(nexusFailure.getOperationId()));

      // Inner cause: ApplicationFailure with the handler's exact failure text and type
      // "OperationError" — same shape as the sync getResult() path. The async path must not
      // alter the cause chain.
      Throwable inner = nexusFailure.getCause();
      Assert.assertNotNull("expected NexusOperationFailedException to wrap an inner cause", inner);
      Assert.assertTrue(
          "expected inner cause to be ApplicationFailure, got " + inner.getClass().getSimpleName(),
          inner instanceof ApplicationFailure);
      ApplicationFailure appFailure = (ApplicationFailure) inner;
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

  // --- helpers ---

  private NexusServiceClient<TestNexusServices.TestNexusService1> buildServiceClient() {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    NexusClient nexusClient =
        NexusClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .build());
    return nexusClient.newNexusServiceClient(
        TestNexusServices.TestNexusService1.class, endpoint.getSpec().getName());
  }

  private UntypedNexusOperationHandle startUntyped(String input) {
    NexusClient client = testWorkflowRule.getNexusClient();
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svcClient =
        client.newUntypedNexusServiceClient(
            endpoint.getSpec().getName(),
            TestNexusServices.TestNexusService1.class.getSimpleName());
    return svcClient.start("operation", newOptionsWithId(), input);
  }

  /** Builds a minimal {@link StartNexusOperationOptions} with a unique id. */
  private static StartNexusOperationOptions newOptionsWithId() {
    return StartNexusOperationOptions.newBuilder().setId(UUID.randomUUID().toString()).build();
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }
}
