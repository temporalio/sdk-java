package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.temporal.api.workflowservice.v1.PauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UnpauseActivityExecutionRequest;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ResetActivityOptions;
import io.temporal.client.UnpauseActivityOptions;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.time.Duration;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for the operator-command request fields the server does not surface back, so they can't
 * be asserted against a real server: the pause/unpause reason, the unpause/reset jitter, and the
 * pause request_id (a dedup UUID). Everything else the commands build — target ids, reset_attempts,
 * reset_heartbeat, keep_paused, restore_original_options, and the update options/mask — is
 * observable via describe and is covered by the real-server tests in {@link
 * io.temporal.client.functional.StandaloneActivityOperatorCommandsTest}.
 */
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
    UntypedActivityHandle handle = newHandle();

    handle.pause("because");
    handle.unpause(
        UnpauseActivityOptions.newBuilder()
            .setReason("go")
            .setJitter(Duration.ofSeconds(5))
            .build());
    handle.reset(ResetActivityOptions.newBuilder().setJitter(Duration.ofSeconds(2)).build());

    // pause carries the reason and an auto-generated dedup request_id; neither is returned by
    // describe.
    PauseActivityExecutionRequest pauseReq = capturePause();
    assertEquals("because", pauseReq.getReason());
    assertTrue("request_id should be set", !pauseReq.getRequestId().isEmpty());

    // unpause carries the reason and jitter; neither is observable on the server.
    UnpauseActivityExecutionRequest unpauseReq = captureUnpause();
    assertEquals("go", unpauseReq.getReason());
    assertEquals(5, unpauseReq.getJitter().getSeconds());

    // reset carries the jitter.
    ResetActivityExecutionRequest resetReq = captureReset();
    assertEquals(2, resetReq.getJitter().getSeconds());
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
}
