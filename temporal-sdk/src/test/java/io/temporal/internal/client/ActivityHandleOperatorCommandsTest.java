package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.time.Duration;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Unit test for the operator-command request fields that the server does not surface back. */
public class ActivityHandleOperatorCommandsTest {

  private final GenericWorkflowClient genericClient = mock(GenericWorkflowClient.class);

  private final ActivityClientOptions clientOptions =
      ActivityClientOptions.newBuilder()
          .setNamespace("test-namespace")
          .setIdentity("test-identity")
          .build();

  private UntypedActivityHandle newHandle() {
    return new ActivityHandleImpl(
        "act-1", "run-1", new RootActivityClientInvoker(genericClient, clientOptions));
  }

  @Test
  public void unobservableRequestFields() {
    // updateActivityOptions returns a non-void response; stub so handle.updateOptions doesn't NPE.
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    UntypedActivityHandle handle = newHandle();

    handle.pause("because");
    handle.unpause(
        UnpauseActivityOptions.newBuilder()
            .setReason("go")
            .setJitter(Duration.ofSeconds(5))
            .build());
    handle.reset(ResetActivityOptions.newBuilder().setJitter(Duration.ofSeconds(2)).build());
    handle.updateOptions(
        UpdateActivityOptions.newBuilder().setStartDelay(Duration.ofSeconds(7)).build());

    // pause carries the reason and an auto-generated dedup request_id; neither is returned by
    // describe.
    PauseActivityExecutionRequest pauseReq = capturePause();
    assertEquals("because", pauseReq.getReason());
    assertTrue("pause request_id should be set", !pauseReq.getRequestId().isEmpty());

    // unpause carries the reason, jitter, and an auto-generated dedup request_id (api#844).
    UnpauseActivityExecutionRequest unpauseReq = captureUnpause();
    assertEquals("go", unpauseReq.getReason());
    assertEquals(5, unpauseReq.getJitter().getSeconds());
    assertEquals(0, unpauseReq.getJitter().getNanos());
    assertTrue("unpause request_id should be set", !unpauseReq.getRequestId().isEmpty());

    // reset carries jitter and an auto-generated dedup request_id (api#844).
    ResetActivityExecutionRequest resetReq = captureReset();
    assertEquals(2, resetReq.getJitter().getSeconds());
    assertEquals(0, resetReq.getJitter().getNanos());
    assertTrue("reset request_id should be set", !resetReq.getRequestId().isEmpty());

    // updateOptions carries start_delay in activity_options with a matching update_mask path, plus
    // an auto-generated dedup request_id (api#844). start_delay is applied server-side but not
    // otherwise observable from the request.
    UpdateActivityExecutionOptionsRequest updateReq = captureUpdate();
    assertEquals(7, updateReq.getActivityOptions().getStartDelay().getSeconds());
    assertEquals(0, updateReq.getActivityOptions().getStartDelay().getNanos());
    assertTrue(
        "update_mask should include start_delay",
        updateReq.getUpdateMask().getPathsList().contains("start_delay"));
    assertTrue("updateOptions request_id should be set", !updateReq.getRequestId().isEmpty());
  }

  private PauseActivityExecutionRequest capturePause() {
    ArgumentCaptor<PauseActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(PauseActivityExecutionRequest.class);
    verify(genericClient).pauseActivity(captor.capture());
    return captor.getValue();
  }

  private UnpauseActivityExecutionRequest captureUnpause() {
    ArgumentCaptor<UnpauseActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(UnpauseActivityExecutionRequest.class);
    verify(genericClient).unpauseActivity(captor.capture());
    return captor.getValue();
  }

  private ResetActivityExecutionRequest captureReset() {
    ArgumentCaptor<ResetActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(ResetActivityExecutionRequest.class);
    verify(genericClient).resetActivity(captor.capture());
    return captor.getValue();
  }

  private UpdateActivityExecutionOptionsRequest captureUpdate() {
    ArgumentCaptor<UpdateActivityExecutionOptionsRequest> captor =
        ArgumentCaptor.forClass(UpdateActivityExecutionOptionsRequest.class);
    verify(genericClient).updateActivityOptions(captor.capture());
    return captor.getValue();
  }
}
