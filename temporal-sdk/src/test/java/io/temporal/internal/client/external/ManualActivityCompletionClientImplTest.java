package io.temporal.internal.client.external;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ManualActivityCompletionClient;
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
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class ManualActivityCompletionClientImplTest {

  private static final byte[] TASK_TOKEN = new byte[] {1, 2, 3};
  private static final String NAMESPACE = "test-namespace";
  private static final String IDENTITY = "test-identity";
  private static final String ACTIVITY_ID = "activity-1";

  private WorkflowServiceStubs service;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;

  @Before
  public void setUp() {
    service = mock(WorkflowServiceStubs.class);
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(service.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.getDefaultInstance());
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

  @Test
  public void recordHeartbeatRetriesTransientErrorsThenSucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenAnswer(
            invocation -> {
              if (attempts.getAndIncrement() < 2) {
                throw new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
              }
              return RecordActivityTaskHeartbeatResponse.getDefaultInstance();
            });

    ManualActivityCompletionClient client = newClientWithTaskToken();
    client.recordHeartbeat("details");

    assertEquals(3, attempts.get());
    verify(blockingStub, times(3)).recordActivityTaskHeartbeat(any());
  }

  @Test
  public void recordHeartbeatByIdRetriesTransientErrorsThenSucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    when(blockingStub.recordActivityTaskHeartbeatById(
            any(RecordActivityTaskHeartbeatByIdRequest.class)))
        .thenAnswer(
            invocation -> {
              if (attempts.getAndIncrement() < 2) {
                throw new StatusRuntimeException(Status.UNAVAILABLE);
              }
              return RecordActivityTaskHeartbeatByIdResponse.getDefaultInstance();
            });

    ManualActivityCompletionClient client = newClientWithActivityId();
    client.recordHeartbeat("details");

    assertEquals(3, attempts.get());
    verify(blockingStub, times(3)).recordActivityTaskHeartbeatById(any());
  }

  @Test
  public void recordHeartbeatDoesNotRetryNotFound() {
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

    ManualActivityCompletionClient client = newClientWithTaskToken();
    assertThrows(ActivityNotExistsException.class, () -> client.recordHeartbeat("details"));
    verify(blockingStub, times(1)).recordActivityTaskHeartbeat(any());
  }

  @Test
  public void recordHeartbeatThrowsCanceledWhenServerRequestsCancel() {
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenReturn(
            RecordActivityTaskHeartbeatResponse.newBuilder().setCancelRequested(true).build());

    ManualActivityCompletionClient client = newClientWithTaskToken();
    assertThrows(ActivityCanceledException.class, () -> client.recordHeartbeat("details"));
  }

  @Test
  public void recordHeartbeatFailsAfterExhaustingRetries() {
    when(blockingStub.recordActivityTaskHeartbeat(any(RecordActivityTaskHeartbeatRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED));

    ManualActivityCompletionClient client = newClientWithTaskToken();
    ActivityCompletionFailureException failure =
        assertThrows(
            ActivityCompletionFailureException.class, () -> client.recordHeartbeat("details"));
    assertTrue(failure.getCause() instanceof StatusRuntimeException);
    assertEquals(
        Status.Code.RESOURCE_EXHAUSTED,
        ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
    verify(blockingStub, times(5)).recordActivityTaskHeartbeat(any());
  }

  private ManualActivityCompletionClient newClientWithTaskToken() {
    return new ManualActivityCompletionClientImpl(
        service,
        NAMESPACE,
        IDENTITY,
        GlobalDataConverter.get(),
        new NoopScope(),
        TASK_TOKEN,
        null,
        null,
        null);
  }

  private ManualActivityCompletionClient newClientWithActivityId() {
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
        null);
  }
}
