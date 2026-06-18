package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.activity.v1.ActivityOptions;
import io.temporal.api.workflowservice.v1.PauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UnpauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ResetActivityOptions;
import io.temporal.client.UnpauseActivityOptions;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.client.UpdateActivityOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Unit test verifying that each operator command on the activity handle flows through the
 * interceptor chain and reaches the gRPC client.
 */
public class ActivityHandleOperatorCommandsTest {

  private final GenericWorkflowClient genericClient = mock(GenericWorkflowClient.class);

  private final ActivityClientOptions clientOptions =
      ActivityClientOptions.newBuilder()
          .setNamespace("test-namespace")
          .setIdentity("test-identity")
          .build();

  private final List<String> recorded = new ArrayList<>();

  private UntypedActivityHandle newHandle() {
    ActivityClientCallsInterceptor root =
        new RootActivityClientInvoker(genericClient, clientOptions);
    ActivityClientCallsInterceptor recording =
        new ActivityClientCallsInterceptorBase(root) {
          @Override
          public PauseActivityOutput pauseActivity(PauseActivityInput input) {
            recorded.add("pause");
            return super.pauseActivity(input);
          }

          @Override
          public UnpauseActivityOutput unpauseActivity(UnpauseActivityInput input) {
            recorded.add("unpause");
            return super.unpauseActivity(input);
          }

          @Override
          public ResetActivityOutput resetActivity(ResetActivityInput input) {
            recorded.add("reset");
            return super.resetActivity(input);
          }

          @Override
          public UpdateActivityOptionsOutput updateActivityOptions(
              UpdateActivityOptionsInput input) {
            recorded.add("updateOptions");
            return super.updateActivityOptions(input);
          }
        };
    return new ActivityHandleImpl("act-1", "run-1", recording);
  }

  @Test
  public void operatorCommandsBuildExpectedRequests() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(
            UpdateActivityExecutionOptionsResponse.newBuilder()
                .setActivityOptions(
                    ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(
                            com.google.protobuf.Duration.newBuilder().setSeconds(30).build()))
                .build());

    UntypedActivityHandle handle = newHandle();

    handle.pause("because");
    handle.unpause(
        UnpauseActivityOptions.newBuilder()
            .setResetAttempts(true)
            .setResetHeartbeat(true)
            .setJitter(Duration.ofSeconds(5))
            .setReason("go")
            .build());
    handle.reset(
        ResetActivityOptions.newBuilder()
            .setResetHeartbeat(true)
            .setKeepPaused(true)
            .setJitter(Duration.ofSeconds(2))
            .build());
    handle.updateOptions(
        UpdateActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    // Each command flowed through the interceptor.
    assertEquals(Arrays.asList("pause", "unpause", "reset", "updateOptions"), recorded);

    // Each command reached the gRPC client with the expected fields.
    PauseActivityExecutionRequest pauseReq = capturePause();
    assertEquals("act-1", pauseReq.getActivityId());
    assertEquals("run-1", pauseReq.getRunId());
    assertEquals("because", pauseReq.getReason());
    assertEquals("", pauseReq.getWorkflowId());
    assertTrue(!pauseReq.getRequestId().isEmpty());

    UnpauseActivityExecutionRequest unpauseReq = captureUnpause();
    assertTrue(unpauseReq.getResetAttempts());
    assertTrue(unpauseReq.getResetHeartbeat());
    assertEquals("go", unpauseReq.getReason());
    assertEquals(5, unpauseReq.getJitter().getSeconds());

    ResetActivityExecutionRequest resetReq = captureReset();
    assertTrue(resetReq.getResetHeartbeat());
    assertTrue(resetReq.getKeepPaused());
    assertEquals(2, resetReq.getJitter().getSeconds());

    UpdateActivityExecutionOptionsRequest updateReq = captureUpdate();
    assertEquals(Arrays.asList("start_to_close_timeout"), updateReq.getUpdateMask().getPathsList());
    assertEquals(30, updateReq.getActivityOptions().getStartToCloseTimeout().getSeconds());
  }

  private PauseActivityExecutionRequest capturePause() {
    org.mockito.ArgumentCaptor<PauseActivityExecutionRequest> captor =
        org.mockito.ArgumentCaptor.forClass(PauseActivityExecutionRequest.class);
    verify(genericClient).pauseActivity(captor.capture());
    return captor.getValue();
  }

  private UnpauseActivityExecutionRequest captureUnpause() {
    org.mockito.ArgumentCaptor<UnpauseActivityExecutionRequest> captor =
        org.mockito.ArgumentCaptor.forClass(UnpauseActivityExecutionRequest.class);
    verify(genericClient).unpauseActivity(captor.capture());
    return captor.getValue();
  }

  private ResetActivityExecutionRequest captureReset() {
    org.mockito.ArgumentCaptor<ResetActivityExecutionRequest> captor =
        org.mockito.ArgumentCaptor.forClass(ResetActivityExecutionRequest.class);
    verify(genericClient).resetActivity(captor.capture());
    return captor.getValue();
  }

  private UpdateActivityExecutionOptionsRequest captureUpdate() {
    org.mockito.ArgumentCaptor<UpdateActivityExecutionOptionsRequest> captor =
        org.mockito.ArgumentCaptor.forClass(UpdateActivityExecutionOptionsRequest.class);
    verify(genericClient).updateActivityOptions(captor.capture());
    return captor.getValue();
  }
}
