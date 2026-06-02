package io.temporal.client.nexus;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationException;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.StandaloneNexusTestPrerequisites;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.BeforeClass;
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

  @BeforeClass
  public static void requireServerWithStandaloneNexusSupport() {
    StandaloneNexusTestPrerequisites.requireServerSupport();
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
      Throwable cause = e.getCause();
      Assert.assertTrue(
          "expected NexusOperationException, got "
              + (cause == null ? "null" : cause.getClass().getSimpleName()),
          cause instanceof NexusOperationException);
      Assert.assertTrue(
          "expected NexusOperationFailedException, got " + cause.getClass().getSimpleName(),
          cause instanceof NexusOperationFailedException);

      // Walk the cause chain and verify the handler's failure message surfaces somewhere.
      boolean foundHandlerFailure = false;
      for (Throwable c = cause.getCause(); c != null; c = c.getCause()) {
        if (c.getMessage() != null && c.getMessage().contains("intentional failure")) {
          foundHandlerFailure = true;
          break;
        }
        if (c.getCause() == c) {
          break;
        }
      }
      Assert.assertTrue(
          "expected cause chain to include the handler's failure message", foundHandlerFailure);
    }
  }

  // --- helpers ---

  private NexusServiceClient<TestNexusServices.TestNexusService1> buildServiceClient() {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    return NexusServiceClient.newInstance(
        TestNexusServices.TestNexusService1.class,
        endpoint.getSpec().getName(),
        testWorkflowRule.getWorkflowServiceStubs(),
        NexusClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .build());
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
