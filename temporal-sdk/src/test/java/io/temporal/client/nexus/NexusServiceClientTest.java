package io.temporal.client.nexus;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.StandaloneNexusTestPrerequisites;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Assert;
import org.junit.BeforeClass;
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
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireServerWithStandaloneNexusSupport() {
    StandaloneNexusTestPrerequisites.requireServerSupport();
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
        testWorkflowRule.getNexusClient().getHandle(handle.getNexusOperationId());
    NexusOperationExecutionDescription description = untyped.describe();
    Assert.assertEquals("per-call-summary", description.getStaticSummary());
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
    return NexusServiceClient.newInstance(
        TestNexusServices.TestNexusService1.class,
        endpoint.getSpec().getName(),
        testWorkflowRule.getWorkflowServiceStubs(),
        NexusClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .build());
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }
}
