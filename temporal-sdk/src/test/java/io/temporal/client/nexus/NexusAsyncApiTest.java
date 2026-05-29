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
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationException;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
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

  private static final Duration FUTURE_GET_TIMEOUT = Duration.ofSeconds(30);
  private static final String FAIL_PREFIX = "FAIL:";

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

  // --- NexusServiceClient.executeAsync ---

  @Test
  public void serviceClientExecuteAsyncReturnsResult() throws Exception {
    String result =
        buildServiceClient()
            .executeAsync(TestNexusServices.TestNexusService1::operation, "hello")
            .get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:hello", result);
  }

  @Test
  public void serviceClientExecuteAsyncWithOptionsReturnsResult() throws Exception {
    StartNexusOperationOptions options =
        StartNexusOperationOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();

    String result =
        buildServiceClient()
            .executeAsync(TestNexusServices.TestNexusService1::operation, "world", options)
            .get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:world", result);
  }

  // --- NexusOperationHandle (typed) getResultAsync overloads ---

  @Test
  public void typedHandleGetResultAsyncReturnsResult() throws Exception {
    NexusOperationHandle<String> handle =
        buildServiceClient().start(TestNexusServices.TestNexusService1::operation, "typed");

    String result = handle.getResultAsync().get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:typed", result);
  }

  @Test
  public void typedHandleGetResultAsyncWithTimeoutReturnsResult() throws Exception {
    NexusOperationHandle<String> handle =
        buildServiceClient().start(TestNexusServices.TestNexusService1::operation, "typed-tm");

    String result =
        handle
            .getResultAsync(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS)
            .get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:typed-tm", result);
  }

  // --- UntypedNexusOperationHandle getResultAsync overloads ---

  @Test
  public void untypedHandleGetResultAsyncByClassReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped");

    String result =
        handle.getResultAsync(String.class).get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:untyped", result);
  }

  @Test
  public void untypedHandleGetResultAsyncByClassAndTypeReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped-gen");

    String result =
        handle
            .getResultAsync(String.class, String.class)
            .get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:untyped-gen", result);
  }

  @Test
  public void untypedHandleGetResultAsyncWithTimeoutByClassReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped-tm");

    String result =
        handle
            .getResultAsync(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS, String.class)
            .get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:untyped-tm", result);
  }

  @Test
  public void untypedHandleGetResultAsyncWithTimeoutByClassAndTypeReturnsResult() throws Exception {
    UntypedNexusOperationHandle handle = startUntyped("untyped-tm-gen");

    String result =
        handle
            .getResultAsync(
                FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS, String.class, String.class)
            .get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

    Assert.assertEquals("echo:untyped-tm-gen", result);
  }

  // --- Failure path ---

  @Test
  public void executeAsyncPropagatesOperationFailure() throws Exception {
    CompletableFuture<String> future =
        buildServiceClient()
            .executeAsync(TestNexusServices.TestNexusService1::operation, FAIL_PREFIX + "boom");

    try {
      future.get(FUTURE_GET_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
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
    return svcClient.start("operation", StartNexusOperationOptions.newBuilder().build(), input);
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return new OperationHandler<String, String>() {
        @Override
        public OperationStartResult<String> start(
            OperationContext context, OperationStartDetails details, String input)
            throws OperationException {
          if (input != null && input.startsWith(FAIL_PREFIX)) {
            throw OperationException.failed("intentional failure: " + input);
          }
          return OperationStartResult.sync("echo:" + (input == null ? "<null>" : input));
        }

        @Override
        public void cancel(OperationContext context, OperationCancelDetails details) {
          // Unused in these tests.
        }
      };
    }
  }
}
