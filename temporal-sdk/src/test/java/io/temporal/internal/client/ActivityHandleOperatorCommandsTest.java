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

import io.temporal.api.workflowservice.v1.PauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UnpauseActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateActivityExecutionOptionsResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityOptionsKeys;
import io.temporal.client.PauseActivityOptions;
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
    handle.updateOptions(ActivityOptionsKeys.START_DELAY.valueSet(Duration.ofSeconds(7)));

    // pause carries the reason, which is not returned by describe.
    PauseActivityExecutionRequest pauseReq = capturePause();
    assertEquals("because", pauseReq.getReason());

    // unpause carries the reason and jitter.
    UnpauseActivityExecutionRequest unpauseReq = captureUnpause();
    assertEquals("go", unpauseReq.getReason());
    assertEquals(5, unpauseReq.getJitter().getSeconds());
    assertEquals(0, unpauseReq.getJitter().getNanos());

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

    assertFalse("unpause should not send jitter", captureUnpause().hasJitter());
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

  private UpdateActivityExecutionOptionsRequest captureUpdate() {
    ArgumentCaptor<UpdateActivityExecutionOptionsRequest> captor =
        ArgumentCaptor.forClass(UpdateActivityExecutionOptionsRequest.class);
    verify(genericClient).updateActivityOptions(captor.capture());
    return captor.getValue();
  }
}
