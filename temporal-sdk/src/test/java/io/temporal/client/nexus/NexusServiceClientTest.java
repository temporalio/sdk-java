package io.temporal.client.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.StartNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptorBase;
import io.temporal.common.interceptors.NexusClientInterceptor;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
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
          .setTestTimeoutSeconds(120)
          .build();

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
  public void clientSummaryIsForwardedIntoStartInput() {
    AtomicReference<StartNexusOperationExecutionInput> captured = new AtomicReference<>();
    RuntimeException sentinel = new RuntimeException("captured-by-test");

    NexusClientInterceptor recordingFactory =
        next ->
            new NexusClientCallsInterceptorBase(next) {
              @Override
              public StartNexusOperationExecutionOutput startNexusOperationExecution(
                  StartNexusOperationExecutionInput input) {
                captured.set(input);
                throw sentinel;
              }
            };

    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        NexusServiceClient.newInstance(
            TestNexusServices.TestNexusService1.class,
            "summary-test-endpoint",
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setInterceptors(Collections.singletonList(recordingFactory))
                .build());

    StartNexusOperationOptions startOptions =
        StartNexusOperationOptions.newBuilder().setSummary("per-call-summary").build();
    try {
      client.start(TestNexusServices.TestNexusService1::operation, "ignored", startOptions);
      Assert.fail("expected sentinel to be thrown by recording interceptor");
    } catch (RuntimeException e) {
      Assert.assertSame(sentinel, e);
    }

    StartNexusOperationExecutionInput input = captured.get();
    Assert.assertNotNull("interceptor should have captured a start input", input);
    Assert.assertEquals(
        "expected summary to be forwarded to the start input",
        "per-call-summary",
        input.getOptions().getSummary());
  }

  @Test
  public void clientSearchAttributesAreEncodedIntoStartInput() {
    SearchAttributeKey<String> customKey = SearchAttributeKey.forKeyword("CustomNexusTestKey");
    SearchAttributes attrs = SearchAttributes.newBuilder().set(customKey, "expected-value").build();

    AtomicReference<StartNexusOperationExecutionInput> captured = new AtomicReference<>();
    RuntimeException sentinel = new RuntimeException("captured-by-test");

    NexusClientInterceptor recordingFactory =
        next ->
            new NexusClientCallsInterceptorBase(next) {
              @Override
              public StartNexusOperationExecutionOutput startNexusOperationExecution(
                  StartNexusOperationExecutionInput input) {
                captured.set(input);
                throw sentinel;
              }
            };

    NexusServiceClient<TestNexusServices.TestNexusService1> client =
        NexusServiceClient.newInstance(
            TestNexusServices.TestNexusService1.class,
            "search-attrs-test-endpoint",
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setInterceptors(Collections.singletonList(recordingFactory))
                .build());

    StartNexusOperationOptions startOptions =
        StartNexusOperationOptions.newBuilder().setTypedSearchAttributes(attrs).build();
    try {
      client.start(TestNexusServices.TestNexusService1::operation, "ignored", startOptions);
      Assert.fail("expected sentinel to be thrown by recording interceptor");
    } catch (RuntimeException e) {
      Assert.assertSame(sentinel, e);
    }

    StartNexusOperationExecutionInput input = captured.get();
    Assert.assertNotNull("interceptor should have captured a start input", input);
    SearchAttributes capturedAttrs = input.getOptions().getTypedSearchAttributes();
    Assert.assertNotNull("expected search attributes to be forwarded", capturedAttrs);
    Assert.assertTrue(
        "expected the custom keyword to be present", capturedAttrs.containsKey(customKey));
    Assert.assertEquals("expected-value", capturedAttrs.get(customKey));
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

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (context, details, input) -> "echo:" + (input == null ? "<null>" : input));
    }
  }
}
