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
import io.temporal.api.activity.v1.ActivityExecutionOutcome;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.api.workflowservice.v1.PauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UnpauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
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

  /**
   * A server that ignores the opt-ins must not be able to make the description's has* accessors
   * disagree with what the caller asked for. Only a stub can produce that response.
   */
  @Test
  public void unrequestedPayloadsAreStripped() {
    when(genericClient.describeActivity(any())).thenReturn(overSharingResponse());

    ActivityExecutionDescription bare = newHandle().describe();
    assertFalse("input should be stripped", bare.hasInput());
    assertFalse("outcome should be stripped", bare.hasResult());
    assertFalse("heartbeat details should be stripped", bare.hasHeartbeatDetails());
    assertFalse("last failure should be stripped", bare.hasLastFailure());
  }

  @Test
  public void requestedPayloadsAreKept() {
    when(genericClient.describeActivity(any())).thenReturn(overSharingResponse());

    ActivityExecutionDescription full =
        newHandle()
            .describe(
                DescribeActivityOptions.newBuilder()
                    .setIncludeInput(true)
                    .setIncludeOutcome(true)
                    .setIncludeHeartbeatDetails(true)
                    .setIncludeLastFailure(true)
                    .build());
    assertTrue(full.hasInput());
    assertTrue(full.hasResult());
    assertTrue(full.hasHeartbeatDetails());
    assertTrue(full.hasLastFailure());
  }

  @Test
  public void strippingIsPerField() {
    when(genericClient.describeActivity(any())).thenReturn(overSharingResponse());

    ActivityExecutionDescription desc =
        newHandle().describe(DescribeActivityOptions.newBuilder().setIncludeInput(true).build());
    assertTrue("input was requested", desc.hasInput());
    assertFalse("outcome was not requested", desc.hasResult());
    assertFalse("heartbeat details were not requested", desc.hasHeartbeatDetails());
    assertFalse("last failure was not requested", desc.hasLastFailure());
  }

  /** A response carrying every payload field, as an older or buggy server might send. */
  private static DescribeActivityExecutionResponse overSharingResponse() {
    Payloads payloads =
        Payloads.newBuilder()
            .addPayloads(
                Payload.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8("x")))
            .build();
    return DescribeActivityExecutionResponse.newBuilder()
        .setInfo(
            ActivityExecutionInfo.newBuilder()
                .setActivityId("act-1")
                .setHeartbeatDetails(payloads)
                .setLastFailure(Failure.newBuilder().setMessage("boom")))
        .setInput(payloads)
        .setOutcome(ActivityExecutionOutcome.newBuilder().setResult(payloads))
        .build();
  }

  /**
   * restoreOriginalOptions reuses the updateActivityOptions call rather than having one of its own,
   * distinguished purely by restore_original with an empty mask. An interceptor watching option
   * updates would otherwise silently miss restores.
   */
  @Test
  public void restoreOriginalOptionsRoutesThroughUpdate() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    newHandle().restoreOriginalOptions();

    UpdateActivityExecutionOptionsRequest req = captureUpdate();
    assertTrue("restore should set restore_original", req.getRestoreOriginal());
    assertTrue(
        "restore should name no paths in the mask", req.getUpdateMask().getPathsList().isEmpty());
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
