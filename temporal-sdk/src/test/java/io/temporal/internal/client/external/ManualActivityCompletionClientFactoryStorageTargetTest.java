package io.temporal.internal.client.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

/**
 * A workflow-scheduled activity must target its workflow no matter which completion entry point is
 * used, so that manual completion and {@link io.temporal.internal.worker.ActivityWorker} select the
 * same driver for the same activity.
 */
public class ManualActivityCompletionClientFactoryStorageTargetTest {

  private static final String NAMESPACE = "test-namespace";

  private final CapturingDriver driver = new CapturingDriver();
  private ManualActivityCompletionClientFactoryImpl factory;

  @Before
  public void setUp() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    when(service.getOptions()).thenReturn(WorkflowServiceStubsOptions.getDefaultInstance());
    factory =
        new ManualActivityCompletionClientFactoryImpl(
            service,
            NAMESPACE,
            "test-identity",
            DefaultDataConverter.newDefaultInstance(),
            ExternalStorageRunner.create(
                ExternalStorage.newBuilder()
                    .setDriver(driver)
                    .setPayloadSizeThreshold(0)
                    .setMaxConcurrentPayloadVisits(1)
                    .build()));
  }

  @Test
  public void byIdWorkflowActivityTargetsItsWorkflow() {
    StorageDriverTargetInfo target =
        capture(
            factory.getClient(
                WorkflowExecution.newBuilder()
                    .setWorkflowId("workflow-id")
                    .setRunId("workflow-run-id")
                    .build(),
                "activity-id",
                new NoopScope(),
                serializationContext()));

    assertEquals(
        new StorageDriverWorkflowInfo(NAMESPACE, "workflow-id", "workflow-run-id", "workflow-type"),
        target);
  }

  @Test
  public void byIdStandaloneActivityTargetsItself() {
    StorageDriverTargetInfo target =
        capture(
            factory.getClient(
                WorkflowExecution.newBuilder().setRunId("activity-run-id").build(),
                "activity-id",
                new NoopScope(),
                serializationContext()));

    assertEquals(
        new StorageDriverActivityInfo(NAMESPACE, "activity-id", "activity-run-id", "activity-type"),
        target);
  }

  @Test
  public void taskTokenWorkflowActivityTargetsItsWorkflow() {
    StorageDriverTargetInfo target =
        capture(factory.getClient(new byte[] {1, 2, 3}, new NoopScope(), serializationContext()));

    assertEquals(
        new StorageDriverWorkflowInfo(NAMESPACE, "workflow-id", null, "workflow-type"), target);
  }

  @Test
  public void taskTokenStandaloneActivityTargetsItself() {
    ActivitySerializationContext standalone =
        new ActivitySerializationContext(NAMESPACE, "", "", "activity-type", "task-queue", false);

    StorageDriverTargetInfo target =
        capture(factory.getClient(new byte[] {1, 2, 3}, new NoopScope(), standalone));

    assertEquals(new StorageDriverActivityInfo(NAMESPACE, null, null, "activity-type"), target);
  }

  private static ActivitySerializationContext serializationContext() {
    return new ActivitySerializationContext(
        NAMESPACE, "workflow-id", "workflow-type", "activity-type", "task-queue", false);
  }

  /**
   * The driver records the target then fails, so completion aborts before any RPC and the test
   * needs no service response.
   */
  private StorageDriverTargetInfo capture(ManualActivityCompletionClient client) {
    driver.lastTarget = null;
    assertThrows(RuntimeException.class, () -> client.complete("result"));
    return driver.lastTarget;
  }

  private static final class CapturingDriver implements StorageDriver {
    volatile StorageDriverTargetInfo lastTarget;

    @Override
    public String getName() {
      return "capturing";
    }

    @Override
    public String getType() {
      return "test.capturing";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      lastTarget = context.getTarget();
      CompletableFuture<List<StorageDriverClaim>> failed = new CompletableFuture<>();
      failed.completeExceptionally(new RuntimeException("storage failed"));
      return failed;
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }
}
