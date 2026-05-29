package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
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
  public void executeReturnsTypedResult() {
    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        buildServiceClient(testWorkflowRule.getNexusEndpoint());

    String result = client.execute(TestNexusServices.TestNexusService1::operation, "hello");

    Assert.assertEquals("echo:hello", result);
  }

  @Test
  public void startReturnsTypedHandleAndPollsResult() {
    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        buildServiceClient(testWorkflowRule.getNexusEndpoint());

    NexusOperationHandle<String> handle =
        client.start(TestNexusServices.TestNexusService1::operation, "world");

    Assert.assertNotNull(handle.getNexusOperationId());
    Assert.assertEquals("echo:world", handle.getResult());
  }

  @Test
  public void clientSummaryReachesServer() {
    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        buildServiceClient(testWorkflowRule.getNexusEndpoint());

    StartNexusOperationOptions startOptions =
        StartNexusOperationOptions.newBuilder().setSummary("per-call-summary").build();
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

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (context, details, input) -> "echo:" + (input == null ? "<null>" : input));
    }
  }
}
