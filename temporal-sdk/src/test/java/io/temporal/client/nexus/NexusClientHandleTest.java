package io.temporal.client.nexus;

import com.google.protobuf.ByteString;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointResponse;
import io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientImpl;
import io.temporal.client.NexusClientOperationExecutionDescription;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusClientHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link UntypedNexusClientHandle} per-execution lifecycle methods returned by {@link
 * NexusClient#getHandle(String)}: {@code describe()}, {@code cancel()}/{@code cancel(reason)}, and
 * {@code terminate()}/{@code terminate(reason)}.
 */
public class NexusClientHandleTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          // Default is 10s; standalone Nexus dispatch + worker poll can take longer.
          .setTestTimeoutSeconds(120)
          .build();

  private NexusClient createNexusClient() {
    return NexusClientImpl.newInstance(
        testWorkflowRule.getWorkflowServiceStubs(),
        NexusClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .build());
  }

  @Test
  public void describeReturnsDescriptionForStartedOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      NexusClientOperationExecutionDescription description = handle.describe();

      Assert.assertNotNull(description);
      Assert.assertNotNull(description.getRunId());
      Assert.assertEquals(started.runId, description.getRunId());
      Assert.assertNotNull(description.getRawResponse());
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void describeWithoutRunIdTargetsLatest() {
    StartedOperation started = startOperation();
    try {
      // Handle with no pinned run ID — server should resolve to the latest run.
      UntypedNexusClientHandle handle = started.client.getHandle(started.operationId);

      NexusClientOperationExecutionDescription description = handle.describe();

      Assert.assertNotNull(description);
      Assert.assertEquals(started.runId, description.getRunId());
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void cancelSucceedsForStartedOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      handle.cancel();
      // No exception — server accepted the cancel request.
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void cancelWithReasonSucceedsForStartedOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      handle.cancel("test-cancel-reason");
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void cancelWithNullReasonSucceeds() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      handle.cancel(null);
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void terminateSucceedsForStartedOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      handle.terminate();
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void terminateWithReasonSucceedsForStartedOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      handle.terminate("test-terminate-reason");
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void terminateWithNullReasonSucceeds() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      handle.terminate(null);
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void getResultReturnsTypedResultForSyncOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle untyped =
          started.client.getHandle(started.operationId, started.runId);

      String result =
          io.temporal.client.NexusClientHandle.fromUntyped(untyped, String.class).getResult();

      Assert.assertNotNull(result);
      Assert.assertTrue("expected echo: prefix, got: " + result, result.startsWith("echo:ping-"));
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void getResultUntypedReturnsResultForSyncOperation() {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

      String result = handle.getResult(String.class);

      Assert.assertNotNull(result);
      Assert.assertTrue(result.startsWith("echo:ping-"));
    } finally {
      cleanup(started);
    }
  }

  @Test
  public void getResultAsyncReturnsTypedResultForSyncOperation() throws Exception {
    StartedOperation started = startOperation();
    try {
      UntypedNexusClientHandle untyped =
          started.client.getHandle(started.operationId, started.runId);

      String result =
          io.temporal.client.NexusClientHandle.fromUntyped(untyped, String.class)
              .getResultAsync()
              .get(60, java.util.concurrent.TimeUnit.SECONDS);

      Assert.assertNotNull(result);
      Assert.assertTrue(result.startsWith("echo:ping-"));
    } finally {
      cleanup(started);
    }
  }

  /** Holder for state used to drive a single test against one started operation. */
  private static final class StartedOperation {
    final NexusClient client;
    final Endpoint endpoint;
    final String operationId;
    final String runId;

    StartedOperation(NexusClient client, Endpoint endpoint, String operationId, String runId) {
      this.client = client;
      this.endpoint = endpoint;
      this.operationId = operationId;
      this.runId = runId;
    }
  }

  private StartedOperation startOperation() {
    return startOperation(null);
  }

  private StartedOperation startOperation(@javax.annotation.Nullable String inputOverride) {
    NexusClient client = createNexusClient();
    Endpoint endpoint = createEndpoint("test-endpoint-" + testWorkflowRule.getTaskQueue());
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
    UntypedNexusClientHandle handle = svcClient.start("operation", opts, inputValue);

    Assert.assertNotNull("expected start to return a run ID", handle.getNexusOperationRunId());
    return new StartedOperation(
        client, endpoint, handle.getNexusOperationId(), handle.getNexusOperationRunId());
  }

  private void cleanup(StartedOperation started) {
    deleteEndpoint(started.endpoint);
  }

  private Endpoint createEndpoint(String name) {
    EndpointSpec spec =
        EndpointSpec.newBuilder()
            .setName(name)
            .setDescription(
                Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")).build())
            .setTarget(
                EndpointTarget.newBuilder()
                    .setWorker(
                        EndpointTarget.Worker.newBuilder()
                            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                            .setTaskQueue(testWorkflowRule.getTaskQueue())))
            .build();
    CreateNexusEndpointResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .createNexusEndpoint(CreateNexusEndpointRequest.newBuilder().setSpec(spec).build());
    return resp.getEndpoint();
  }

  private void deleteEndpoint(Endpoint endpoint) {
    testWorkflowRule
        .getTestEnvironment()
        .getOperatorServiceStubs()
        .blockingStub()
        .deleteNexusEndpoint(
            DeleteNexusEndpointRequest.newBuilder()
                .setId(endpoint.getId())
                .setVersion(endpoint.getVersion())
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
    StartedOperation started = startOperation(TestNexusServiceImpl.FAIL_PREFIX + "boom");
    try {
      UntypedNexusClientHandle handle =
          started.client.getHandle(started.operationId, started.runId);

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
    } finally {
      cleanup(started);
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
