package io.temporal.internal.client.external;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

public class ManualActivityCompletionClientImplTest {
  private final RuntimeException storageFailure = new RuntimeException("storage failed");
  private WorkflowServiceStubs service;
  private ExternalStorage externalStorage;

  @Before
  public void setUp() {
    service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    when(service.getOptions()).thenReturn(WorkflowServiceStubsOptions.getDefaultInstance());
    externalStorage =
        ExternalStorage.create(
            ExternalStorageOptions.newBuilder()
                .setDriver(new FailingDriver())
                .setPayloadSizeThreshold(0)
                .setMaxConcurrentPayloadVisits(1)
                .build());
  }

  @Test
  public void taskTokenCompletionWrapsStorageFailure() {
    ManualActivityCompletionClientImpl client = taskTokenClient();

    ActivityCompletionFailureException failure =
        assertThrows(ActivityCompletionFailureException.class, () -> client.complete("result"));

    assertSame(storageFailure, failure.getCause());
    verify(service, never()).blockingStub();
  }

  @Test
  public void byIdFailureWrapsStorageFailure() {
    ManualActivityCompletionClientImpl client = byIdClient();

    ActivityCompletionFailureException failure =
        assertThrows(
            ActivityCompletionFailureException.class,
            () -> client.fail(ApplicationFailure.newFailure("activity failed", "test", "details")));

    assertSame(storageFailure, failure.getCause());
    verify(service, never()).blockingStub();
  }

  @Test
  public void taskTokenCancellationIgnoresStorageFailure() {
    taskTokenClient().reportCancellation("details");

    verify(service, never()).blockingStub();
  }

  @Test
  public void byIdCancellationIgnoresStorageFailure() {
    byIdClient().reportCancellation("details");

    verify(service, never()).blockingStub();
  }

  private ManualActivityCompletionClientImpl taskTokenClient() {
    return new ManualActivityCompletionClientImpl(
        service,
        "test-namespace",
        "test-identity",
        DefaultDataConverter.newDefaultInstance(),
        new NoopScope(),
        new byte[] {1, 2, 3},
        null,
        null,
        null,
        new StorageDriverActivityInfo(
            "test-namespace", "activity-id", "activity-run-id", "activity-type"),
        externalStorage);
  }

  private ManualActivityCompletionClientImpl byIdClient() {
    return new ManualActivityCompletionClientImpl(
        service,
        "test-namespace",
        "test-identity",
        DefaultDataConverter.newDefaultInstance(),
        new NoopScope(),
        null,
        WorkflowExecution.newBuilder().setRunId("activity-run-id").build(),
        "activity-id",
        null,
        new StorageDriverActivityInfo(
            "test-namespace", "activity-id", "activity-run-id", "activity-type"),
        externalStorage);
  }

  private final class FailingDriver implements StorageDriver {
    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      CompletableFuture<List<StorageDriverClaim>> result = new CompletableFuture<>();
      result.completeExceptionally(storageFailure);
      return result;
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }
}
