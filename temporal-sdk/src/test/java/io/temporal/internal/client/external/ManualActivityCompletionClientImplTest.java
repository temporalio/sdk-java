package io.temporal.internal.client.external;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityPausedException;
import io.temporal.client.ActivityResetException;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.junit.Before;
import org.junit.Test;

public class ManualActivityCompletionClientImplTest {

  private WorkflowServiceStubs service;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;

  @Before
  public void setUp() {
    service = mock(WorkflowServiceStubs.class);
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(service.getServerCapabilities())
        .thenReturn(
            () ->
                io.temporal.api.workflowservice.v1.GetSystemInfoResponse.Capabilities
                    .getDefaultInstance());
    when(service.getOptions())
        .thenReturn(WorkflowServiceStubsOptions.newBuilder().validateAndBuildWithDefaults());
  }

  private ManualActivityCompletionClientImpl clientWithTaskToken() {
    return new ManualActivityCompletionClientImpl(
        service,
        "test-namespace",
        "test-identity",
        GlobalDataConverter.get(),
        new NoopScope(),
        new byte[] {1, 2, 3},
        null,
        null,
        null);
  }

  private ManualActivityCompletionClientImpl clientWithActivityId() {
    return new ManualActivityCompletionClientImpl(
        service,
        "test-namespace",
        "test-identity",
        GlobalDataConverter.get(),
        new NoopScope(),
        null,
        WorkflowExecution.newBuilder().setWorkflowId("wf").setRunId("run").build(),
        "test-activity-id",
        null);
  }

  @Test
  public void cancelRequestedThrowsActivityCanceledExceptionNotSwallowed() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(
            RecordActivityTaskHeartbeatResponse.newBuilder().setCancelRequested(true).build());

    assertThrows(
        ActivityCanceledException.class, () -> clientWithTaskToken().recordHeartbeat("details"));
  }

  @Test
  public void activityResetThrowsActivityResetExceptionNotSwallowed() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(
            RecordActivityTaskHeartbeatResponse.newBuilder().setActivityReset(true).build());

    assertThrows(
        ActivityResetException.class, () -> clientWithTaskToken().recordHeartbeat("details"));
  }

  @Test
  public void activityPausedThrowsActivityPausedExceptionNotSwallowed() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenReturn(
            RecordActivityTaskHeartbeatResponse.newBuilder().setActivityPaused(true).build());

    assertThrows(
        ActivityPausedException.class, () -> clientWithTaskToken().recordHeartbeat("details"));
  }

  @Test
  public void byIdCancelRequestedThrowsActivityCanceledExceptionNotSwallowed() {
    when(blockingStub.recordActivityTaskHeartbeatById(any()))
        .thenReturn(
            RecordActivityTaskHeartbeatByIdResponse.newBuilder().setCancelRequested(true).build());

    assertThrows(
        ActivityCanceledException.class, () -> clientWithActivityId().recordHeartbeat("details"));
  }

  @Test
  public void transientRpcErrorIsRetriedThenSucceeds() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenThrow(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED))
        .thenReturn(RecordActivityTaskHeartbeatResponse.getDefaultInstance());

    // Should not throw: the transient error is retried and the second attempt succeeds.
    clientWithTaskToken().recordHeartbeat("details");

    verify(blockingStub, times(2)).recordActivityTaskHeartbeat(any());
  }

  @Test
  public void nonTransientRpcErrorIsReportedAsActivityCompletionFailureException() {
    when(blockingStub.recordActivityTaskHeartbeat(any()))
        .thenThrow(new StatusRuntimeException(Status.INTERNAL));

    assertThrows(
        ActivityCompletionFailureException.class,
        () -> clientWithTaskToken().recordHeartbeat("details"));
  }
}
