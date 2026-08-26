package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.api.workflowservice.v1.PauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UnpauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.DescribeActivityOptions;
import io.temporal.client.PauseActivityOptions;
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

    handle.pause(PauseActivityOptions.newBuilder().setReason("because").build());
    handle.unpause(
        UnpauseActivityOptions.newBuilder()
            .setReason("go")
            .setJitter(Duration.ofSeconds(5))
            .build());
    handle.reset(
        ResetActivityOptions.newBuilder()
            .setJitter(Duration.ofSeconds(2))
            .setResetHeartbeat(true)
            .setKeepPaused(true)
            .setRestoreOriginalOptions(true)
            .build());
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

    // reset carries jitter, an auto-generated dedup request_id (api#844), and reset_heartbeat
    // (api#848).
    ResetActivityExecutionRequest resetReq = captureReset();
    assertEquals(2, resetReq.getJitter().getSeconds());
    assertEquals(0, resetReq.getJitter().getNanos());
    assertTrue("reset request_id should be set", !resetReq.getRequestId().isEmpty());
    assertTrue("reset should carry reset_heartbeat=true", resetReq.getResetHeartbeat());
    assertTrue("reset should carry keep_paused=true", resetReq.getKeepPaused());
    assertTrue(
        "reset should carry restore_original_options=true", resetReq.getRestoreOriginalOptions());

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

  /**
   * An update naming no options would send an empty mask and silently change nothing, so it is
   * rejected before the round trip. Reverting options is {@link
   * UntypedActivityHandle#restoreOriginalOptions()}, which the server does not allow to be combined
   * with individual changes.
   */
  @Test
  public void updateOptionsRequiresAtLeastOneOption() {
    UntypedActivityHandle handle = newHandle();

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> handle.updateOptions(UpdateActivityOptions.newBuilder().build()));
    assertTrue(e.getMessage().contains("at least one option"));

    verifyNoInteractions(genericClient);
  }

  /** A single set option is enough; the mask names exactly it. */
  @Test
  public void updateOptionsAcceptsASingleOption() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    newHandle()
        .updateOptions(
            UpdateActivityOptions.newBuilder().setHeartbeatTimeout(Duration.ofSeconds(25)).build());

    assertEquals(
        java.util.Collections.singletonList("heartbeat_timeout"),
        captureUpdate().getUpdateMask().getPathsList());
  }

  /**
   * The four api#792 opt-ins are invisible in any observable server state, so only the outgoing
   * request shows whether the SDK asked for them.
   */
  @Test
  public void describeOptInsReachTheRequest() {
    when(genericClient.describeActivity(any()))
        .thenReturn(
            DescribeActivityExecutionResponse.newBuilder()
                .setInfo(ActivityExecutionInfo.newBuilder().setActivityId("act-1"))
                .build());

    newHandle().describe();
    DescribeActivityExecutionRequest bare = captureDescribe();
    assertFalse("default should not request input", bare.getIncludeInput());
    assertFalse("default should not request outcome", bare.getIncludeOutcome());
    assertFalse("default should not request heartbeat details", bare.getIncludeHeartbeatDetails());
    assertFalse("default should not request last failure", bare.getIncludeLastFailure());
  }

  @Test
  public void describeOptInsAreForwardedAndIndependent() {
    when(genericClient.describeActivity(any()))
        .thenReturn(
            DescribeActivityExecutionResponse.newBuilder()
                .setInfo(ActivityExecutionInfo.newBuilder().setActivityId("act-1"))
                .build());

    newHandle()
        .describe(
            DescribeActivityOptions.newBuilder()
                .setIncludeInput(true)
                .setIncludeOutcome(true)
                .setIncludeHeartbeatDetails(true)
                .setIncludeLastFailure(true)
                .build());
    DescribeActivityExecutionRequest all = captureDescribe();
    assertTrue(all.getIncludeInput());
    assertTrue(all.getIncludeOutcome());
    assertTrue(all.getIncludeHeartbeatDetails());
    assertTrue(all.getIncludeLastFailure());
  }

  @Test
  public void describeOptInSetsOnlyTheRequestedFlag() {
    when(genericClient.describeActivity(any()))
        .thenReturn(
            DescribeActivityExecutionResponse.newBuilder()
                .setInfo(ActivityExecutionInfo.newBuilder().setActivityId("act-1"))
                .build());

    newHandle().describe(DescribeActivityOptions.newBuilder().setIncludeInput(true).build());
    DescribeActivityExecutionRequest one = captureDescribe();
    assertTrue(one.getIncludeInput());
    assertFalse(one.getIncludeOutcome());
    assertFalse(one.getIncludeHeartbeatDetails());
    assertFalse(one.getIncludeLastFailure());
  }

  private DescribeActivityExecutionRequest captureDescribe() {
    ArgumentCaptor<DescribeActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(DescribeActivityExecutionRequest.class);
    verify(genericClient).describeActivity(captor.capture());
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
