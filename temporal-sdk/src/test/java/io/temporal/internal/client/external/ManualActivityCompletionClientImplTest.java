package io.temporal.internal.client.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class ManualActivityCompletionClientImplTest {
  private static final byte[] TASK_TOKEN = new byte[] {1, 2, 3};
  private static final String NAMESPACE = "test-namespace";
  private static final String IDENTITY = "test-identity";
  private static final String ACTIVITY_ID = "activity-1";

  private final RuntimeException storageFailure = new RuntimeException("storage failed");
  private WorkflowServiceStubs service;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private ExternalStorageRunner externalStorage;

  @Before
  public void setUp() {
    service = mock(WorkflowServiceStubs.class);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
    when(service.getOptions()).thenReturn(WorkflowServiceStubsOptions.getDefaultInstance());
    externalStorage =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder()
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

  @Test
  public void recordHeartbeatRetriesTransientErrorsThenSucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    prepareHeartbeatStub();
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenAnswer(
            invocation -> {
              if (attempts.getAndIncrement() < 2) {
                throw new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
              }
              return RecordActivityTaskHeartbeatResponse.getDefaultInstance();
            });

    newClientWithTaskToken().recordHeartbeat("details");

    assertEquals(3, attempts.get());
    verify(blockingStub, times(3)).recordActivityTaskHeartbeat(any());
  }

  @Test
  public void recordHeartbeatByIdRetriesTransientErrorsThenSucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    prepareHeartbeatStub();
    when(blockingStub.recordActivityTaskHeartbeatById(
            any(RecordActivityTaskHeartbeatByIdRequest.class)))
        .thenAnswer(
            invocation -> {
              if (attempts.getAndIncrement() < 2) {
                throw new StatusRuntimeException(Status.UNAVAILABLE);
              }
              return RecordActivityTaskHeartbeatByIdResponse.getDefaultInstance();
            });

    newClientWithActivityId().recordHeartbeat("details");

    assertEquals(3, attempts.get());
    verify(blockingStub, times(3)).recordActivityTaskHeartbeatById(any());
  }

  @Test
  public void recordHeartbeatDoesNotRetryNotFound() {
    prepareHeartbeatStub();
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

    assertThrows(
        ActivityNotExistsException.class,
        () -> newClientWithTaskToken().recordHeartbeat("details"));
    verify(blockingStub, times(1)).recordActivityTaskHeartbeat(any());
  }

  @Test
  public void recordHeartbeatThrowsCanceledWhenServerRequestsCancel() {
    prepareHeartbeatStub();
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenReturn(
            RecordActivityTaskHeartbeatResponse.newBuilder().setCancelRequested(true).build());

    assertThrows(
        ActivityCanceledException.class, () -> newClientWithTaskToken().recordHeartbeat("details"));
  }

  @Test
  public void recordHeartbeatFailsAfterExhaustingRetries() {
    prepareHeartbeatStub();
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED));

    ActivityCompletionFailureException failure =
        assertThrows(
            ActivityCompletionFailureException.class,
            () -> newClientWithTaskToken().recordHeartbeat("details"));
    assertTrue(failure.getCause() instanceof StatusRuntimeException);
    assertEquals(
        Status.Code.RESOURCE_EXHAUSTED,
        ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    verify(blockingStub, times(5)).recordActivityTaskHeartbeat(any());
  }

  private void prepareHeartbeatStub() {
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(service.getOptions())
        .thenReturn(
            WorkflowServiceStubsOptions.newBuilder()
                .setRpcRetryOptions(
                    RpcRetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(1))
                        .setCongestionInitialInterval(Duration.ofMillis(1))
                        .setMaximumInterval(Duration.ofMillis(10))
                        .setExpiration(Duration.ofSeconds(2))
                        .setMaximumAttempts(5)
                        .setBackoffCoefficient(1.0)
                        .validateBuildWithDefaults())
                .validateAndBuildWithDefaults());
  }

  private ManualActivityCompletionClientImpl newClientWithTaskToken() {
    return new ManualActivityCompletionClientImpl(
        service,
        NAMESPACE,
        IDENTITY,
        GlobalDataConverter.get(),
        new NoopScope(),
        TASK_TOKEN,
        null,
        null,
        null,
        null,
        null);
  }

  private ManualActivityCompletionClientImpl newClientWithActivityId() {
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId("wf-id").setRunId("run-id").build();
    return new ManualActivityCompletionClientImpl(
        service,
        NAMESPACE,
        IDENTITY,
        GlobalDataConverter.get(),
        new NoopScope(),
        null,
        execution,
        ACTIVITY_ID,
        null,
        null,
        null);
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
