package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
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
 * End-to-end tests for {@link NexusServiceClient}: typed start/execute via {@link
 * java.util.function.BiFunction} method references.
 */
public class NexusServiceClientTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(
              new EchoNexusServiceImpl(),
              new VoidInputServiceImpl(),
              new VoidReturnServiceImpl(),
              new VoidServiceImpl())
          .build();

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.supportsStandaloneNexusOperations());
  }

  @Test
  public void executeReturnsTypedResult() {
    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        buildServiceClient(testWorkflowRule.getNexusEndpoint());

    String result =
        client.execute(TestNexusServices.TestNexusService1::operation, "hello", newOptionsWithId());

    Assert.assertEquals("echo:hello", result);
  }

  @Test
  public void startReturnsTypedHandleAndPollsResult() {
    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        buildServiceClient(testWorkflowRule.getNexusEndpoint());

    NexusOperationHandle<String> handle =
        client.start(TestNexusServices.TestNexusService1::operation, "world", newOptionsWithId());

    Assert.assertNotNull(handle.getNexusOperationId());
    Assert.assertEquals("echo:world", handle.getResult());
  }

  @Test
  public void executeWithOptionsReturnsResult() {
    // Covers the 3-arg execute(op, input, options) overload — exercises a non-default
    // scheduleToCloseTimeout in addition to the required id.
    StartNexusOperationOptions options =
        StartNexusOperationOptions.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();

    String result =
        buildServiceClient(testWorkflowRule.getNexusEndpoint())
            .execute(TestNexusServices.TestNexusService1::operation, "with-opts", options);

    Assert.assertEquals("echo:with-opts", result);
  }

  @Test
  public void startWithExplicitIdHonoursId() {
    String explicitId = "explicit-id-" + UUID.randomUUID();
    StartNexusOperationOptions options =
        StartNexusOperationOptions.newBuilder()
            .setId(explicitId)
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();

    NexusOperationHandle<String> handle =
        buildServiceClient(testWorkflowRule.getNexusEndpoint())
            .start(TestNexusServices.TestNexusService1::operation, "id-test", options);

    Assert.assertEquals(
        "explicit ID supplied via StartNexusOperationOptions.setId must round-trip on the handle",
        explicitId,
        handle.getNexusOperationId());
    // Sanity-check: the operation still completes normally with the explicit ID.
    Assert.assertEquals("echo:id-test", handle.getResult());
  }

  @Test
  public void clientSummaryReachesServer() {
    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        buildServiceClient(testWorkflowRule.getNexusEndpoint());

    StartNexusOperationOptions startOptions =
        StartNexusOperationOptions.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setSummary("per-call-summary")
            .build();
    NexusOperationHandle<String> handle =
        client.start(TestNexusServices.TestNexusService1::operation, "world", startOptions);

    // Describe round-trips the operation through the server, proving the summary was actually
    // persisted on the server-side record rather than just forwarded through the local interceptor
    // chain.
    UntypedNexusOperationHandle untyped =
        testWorkflowRule.getNexusClient().getHandle(handle.getNexusOperationId(), null);
    NexusOperationExecutionDescription description = untyped.describe();
    Assert.assertEquals("per-call-summary", description.getStaticSummary());
  }

  // ── No-input / void-return shape coverage ────────────────────────────────────────────────

  @Test
  public void executeWithNoInputReturnsResult() {
    // Exercises the Function<T, R> overload — Nexus operations declared as `R operation()` with
    // no input parameter.
    NexusServiceClient<TestNexusServices.TestNexusServiceVoidInput> client =
        buildServiceClientFor(TestNexusServices.TestNexusServiceVoidInput.class);

    String result =
        client.execute(TestNexusServices.TestNexusServiceVoidInput::operation, newOptionsWithId());

    Assert.assertEquals("void-input-result", result);
  }

  @Test
  public void executeWithVoidReturnCompletes() {
    // Exercises the existing BiFunction<T, U, Void> path — Nexus operations declared as
    // `Void operation(input)`. No new overload needed; the SDK already handles Void as a result
    // type. This test pins that contract.
    //
    // Because the result is `null`, asserting only that the return value is null is too weak —
    // an operation that never ran would also produce null. The handler bumps a counter so we
    // know it actually executed.
    NexusServiceClient<TestNexusServices.TestNexusServiceVoidReturn> client =
        buildServiceClientFor(TestNexusServices.TestNexusServiceVoidReturn.class);
    int before = VoidReturnServiceImpl.invocations.get();

    Void result =
        client.execute(
            TestNexusServices.TestNexusServiceVoidReturn::operation, "ignored", newOptionsWithId());

    Assert.assertNull(result);
    Assert.assertEquals(
        "expected the void-return handler to be invoked exactly once",
        before + 1,
        VoidReturnServiceImpl.invocations.get());
  }

  @Test
  public void executeWithNoInputAndVoidReturnCompletes() {
    // Exercises the Function<T, Void> overload — Nexus operations declared as `Void operation()`
    // with neither input nor a useful return value.
    NexusServiceClient<TestNexusServices.TestNexusServiceVoid> client =
        buildServiceClientFor(TestNexusServices.TestNexusServiceVoid.class);

    Void result =
        client.execute(TestNexusServices.TestNexusServiceVoid::operation, newOptionsWithId());

    Assert.assertNull(result);
  }

  // A search-attribute round-trip via describe() would naturally belong here, but the rule's
  // `registerSearchAttribute(...)` is asynchronous on the server side and races the test —
  // calling `start(...)` immediately afterwards fails with "no mapping defined for search
  // attribute" until the namespace's Visibility index catches up. Reintroduce once the rule
  // (or the test) synchronously waits for the mapping to propagate.

  /** Builds a minimal {@link StartNexusOperationOptions} with a unique id. */
  private static StartNexusOperationOptions newOptionsWithId() {
    return StartNexusOperationOptions.newBuilder().setId(UUID.randomUUID().toString()).build();
  }

  private NexusServiceClient<TestNexusServices.TestNexusService1> buildServiceClient(
      Endpoint endpoint) {
    return buildServiceClientFor(TestNexusServices.TestNexusService1.class);
  }

  /** Builds a typed client for an arbitrary Nexus service interface against the rule's endpoint. */
  private <S> NexusServiceClient<S> buildServiceClientFor(Class<S> serviceClass) {
    NexusClient nexusClient =
        NexusClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .build());
    return nexusClient.newNexusServiceClient(
        serviceClass, testWorkflowRule.getNexusEndpoint().getSpec().getName());
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  /** Handler for the no-input, has-output service {@code TestNexusServiceVoidInput}. */
  @ServiceImpl(service = TestNexusServices.TestNexusServiceVoidInput.class)
  public static class VoidInputServiceImpl {
    @OperationImpl
    public OperationHandler<Void, String> operation() {
      return OperationHandler.sync((ctx, details, input) -> "void-input-result");
    }
  }

  /**
   * Handler for the has-input, void-return service {@code TestNexusServiceVoidReturn}. Counts
   * invocations so {@link #executeWithVoidReturnCompletes} can prove the handler actually ran (a
   * null return value alone is ambiguous between "handler ran and returned Void" and "handler never
   * ran").
   */
  @ServiceImpl(service = TestNexusServices.TestNexusServiceVoidReturn.class)
  public static class VoidReturnServiceImpl {
    static final java.util.concurrent.atomic.AtomicInteger invocations =
        new java.util.concurrent.atomic.AtomicInteger();

    @OperationImpl
    public OperationHandler<String, Void> operation() {
      return OperationHandler.sync(
          (ctx, details, input) -> {
            invocations.incrementAndGet();
            return null;
          });
    }
  }

  /** Handler for the no-input, void-return service {@code TestNexusServiceVoid}. */
  @ServiceImpl(service = TestNexusServices.TestNexusServiceVoid.class)
  public static class VoidServiceImpl {
    @OperationImpl
    public OperationHandler<Void, Void> operation() {
      return OperationHandler.sync((ctx, details, input) -> null);
    }
  }
}
