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
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.api.workflowservice.v1.PauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UnpauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityOptionsKeys;
import io.temporal.client.DescribeActivityOptions;
import io.temporal.client.PauseActivityOptions;
import io.temporal.client.ResetActivityOptions;
import io.temporal.client.UnpauseActivityOptions;
import io.temporal.client.UntypedActivityHandle;
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
    handle.updateOptions(ActivityOptionsKeys.START_DELAY.valueSet(Duration.ofSeconds(7)));

    // pause carries the reason, which is not returned by describe.
    PauseActivityExecutionRequest pauseReq = capturePause();
    assertEquals("because", pauseReq.getReason());

    // unpause carries the reason and jitter.
    UnpauseActivityExecutionRequest unpauseReq = captureUnpause();
    assertEquals("go", unpauseReq.getReason());
    assertEquals(5, unpauseReq.getJitter().getSeconds());
    assertEquals(0, unpauseReq.getJitter().getNanos());

    // reset carries jitter and reset_heartbeat.
    ResetActivityExecutionRequest resetReq = captureReset();
    assertEquals(2, resetReq.getJitter().getSeconds());
    assertEquals(0, resetReq.getJitter().getNanos());
    assertTrue("reset should carry reset_heartbeat=true", resetReq.getResetHeartbeat());
    assertTrue("reset should carry keep_paused=true", resetReq.getKeepPaused());
    assertTrue(
        "reset should carry restore_original_options=true", resetReq.getRestoreOriginalOptions());

    // updateOptions carries start_delay in activity_options with a matching update_mask path.
    UpdateActivityExecutionOptionsRequest updateReq = captureUpdate();
    assertEquals(7, updateReq.getActivityOptions().getStartDelay().getSeconds());
    assertEquals(0, updateReq.getActivityOptions().getStartDelay().getNanos());
    assertTrue(
        "update_mask should include start_delay",
        updateReq.getUpdateMask().getPathsList().contains("start_delay"));
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
        assertThrows(IllegalArgumentException.class, () -> handle.updateOptions());
    assertTrue(e.getMessage().contains("at least one option"));

    verifyNoInteractions(genericClient);
  }

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
   * ValueSet of a zero duration is an explicit zero, not a clear: the path is named in the mask and
   * the field is present holding zero. The server normalizes a zero timeout to unset, but that is
   * the server's decision, not something the SDK decides on the caller's behalf.
   */
  @Test
  public void valueSetOfZeroSendsAnExplicitZero() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    newHandle().updateOptions(ActivityOptionsKeys.HEARTBEAT_TIMEOUT.valueSet(Duration.ZERO));

    UpdateActivityExecutionOptionsRequest req = captureUpdate();
    assertEquals(
        java.util.Collections.singleton("heartbeat_timeout"),
        new java.util.HashSet<>(req.getUpdateMask().getPathsList()));
    assertTrue(
        "a zero value is present, not absent", req.getActivityOptions().hasHeartbeatTimeout());
    assertEquals(0, req.getActivityOptions().getHeartbeatTimeout().getSeconds());
    assertEquals(0, req.getActivityOptions().getHeartbeatTimeout().getNanos());
  }

  /**
   * ValueUnset names the path but leaves the field absent, which is how the server is told to clear
   * the option rather than set it to a value.
   */
  @Test
  public void valueUnsetNamesThePathButLeavesTheFieldAbsent() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    newHandle().updateOptions(ActivityOptionsKeys.HEARTBEAT_TIMEOUT.valueUnset());

    UpdateActivityExecutionOptionsRequest req = captureUpdate();
    assertEquals(
        java.util.Collections.singleton("heartbeat_timeout"),
        new java.util.HashSet<>(req.getUpdateMask().getPathsList()));
    assertFalse(
        "an unset value is absent, not zero", req.getActivityOptions().hasHeartbeatTimeout());
  }

  @Test
  public void omittedJitterIsLeftOffTheWire() {
    UntypedActivityHandle handle = newHandle();

    handle.unpause(UnpauseActivityOptions.newBuilder().build());
    handle.reset(ResetActivityOptions.newBuilder().build());

    assertFalse("unpause should not send jitter", captureUnpause().hasJitter());
    assertFalse("reset should not send jitter", captureReset().hasJitter());
  }

  /** A repeated key resolves to its last update: a later valueUnset overrides an earlier set. */
  @Test
  public void aRepeatedKeyResolvesToItsLastUpdate() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    newHandle()
        .updateOptions(
            ActivityOptionsKeys.HEARTBEAT_TIMEOUT.valueSet(Duration.ofSeconds(5)),
            ActivityOptionsKeys.HEARTBEAT_TIMEOUT.valueUnset());

    UpdateActivityExecutionOptionsRequest req = captureUpdate();
    // The later unset wins, and the path is named once.
    assertEquals(
        java.util.Collections.singleton("heartbeat_timeout"),
        new java.util.HashSet<>(req.getUpdateMask().getPathsList()));
    assertFalse(req.getActivityOptions().hasHeartbeatTimeout());
  }

  /** The mask names exactly the options that were updated, and nothing else. */
  @Test
  public void maskNamesOnlyTheChangedOptions() {
    when(genericClient.updateActivityOptions(any()))
        .thenReturn(UpdateActivityExecutionOptionsResponse.getDefaultInstance());

    newHandle()
        .updateOptions(
            ActivityOptionsKeys.TASK_QUEUE.valueSet("new-tq"),
            ActivityOptionsKeys.START_TO_CLOSE_TIMEOUT.valueSet(Duration.ofSeconds(90)));

    UpdateActivityExecutionOptionsRequest req = captureUpdate();
    assertEquals(
        new java.util.HashSet<>(
            java.util.Arrays.asList("task_queue.name", "start_to_close_timeout")),
        new java.util.HashSet<>(req.getUpdateMask().getPathsList()));
    assertFalse(req.getRestoreOriginal());
    assertEquals("new-tq", req.getActivityOptions().getTaskQueue().getName());
    assertEquals(90, req.getActivityOptions().getStartToCloseTimeout().getSeconds());
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
