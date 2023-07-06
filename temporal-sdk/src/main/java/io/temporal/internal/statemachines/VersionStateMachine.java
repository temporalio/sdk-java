/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.statemachines;

import static io.temporal.internal.sync.WorkflowInternal.DEFAULT_VERSION;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.worker.NonDeterministicException;
import io.temporal.workflow.Functions;
import java.util.Objects;
import javax.annotation.Nullable;

final class VersionStateMachine {
  private static final String RETROACTIVE_ADDITION_ERROR_STRING =
      "The most probable cause is retroactive addition of a getVersion call with an existing 'changeId'";

  private final String changeId;
  private final Functions.Func<Boolean> replaying;
  private final Functions.Proc1<CancellableCommand> commandSink;
  private final Functions.Proc1<StateMachine> stateMachineSink;

  @Nullable private Integer version;
  /**
   * This variable is used for replay only. When we replay, we look one workflow task ahead and
   * preload all version markers to be able to return from Workflow.getVersion called in the event
   * loop the same way as we return during the original execution (without a full block to trigger
   * workflow task end and match with command events) These preloaded versions are converted and
   * moved into {@link #version} field when we actually process and match the version marker event
   * after workflow task is finished / event loop is blocked.
   */
  @Nullable private Integer preloadedVersion;

  enum ExplicitEvent {
    CHECK_EXECUTION_STATE,
    SCHEDULE,
    NON_MATCHING_EVENT
  }

  enum State {
    CREATED,

    EXECUTING,
    MARKER_COMMAND_CREATED,
    SKIPPED,
    RESULT_NOTIFIED,

    REPLAYING,
    MARKER_COMMAND_CREATED_REPLAYING,
    SKIPPED_REPLAYING,
    RESULT_NOTIFIED_REPLAYING,

    MARKER_COMMAND_RECORDED,
    SKIPPED_NOTIFIED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, InvocationStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, InvocationStateMachine>newInstance(
                  "Version", State.CREATED, State.MARKER_COMMAND_RECORDED, State.SKIPPED_NOTIFIED)
              .add(
                  State.CREATED,
                  ExplicitEvent.CHECK_EXECUTION_STATE,
                  new State[] {State.REPLAYING, State.EXECUTING},
                  InvocationStateMachine::getExecutionState)
              .add(
                  State.EXECUTING,
                  ExplicitEvent.SCHEDULE,
                  new State[] {State.MARKER_COMMAND_CREATED, State.SKIPPED},
                  InvocationStateMachine::createMarkerExecuting)
              .add(
                  State.MARKER_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED,
                  InvocationStateMachine::notifyFromVersionExecuting)
              .add(
                  State.RESULT_NOTIFIED,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED)
              .add(
                  State.SKIPPED,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.SKIPPED_NOTIFIED,
                  InvocationStateMachine::notifySkippedExecuting)
              .add(
                  State.REPLAYING,
                  ExplicitEvent.SCHEDULE,
                  new State[] {State.MARKER_COMMAND_CREATED_REPLAYING, State.SKIPPED_REPLAYING},
                  InvocationStateMachine::createMarkerReplaying)
              .add(
                  State.MARKER_COMMAND_CREATED_REPLAYING,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED_REPLAYING,
                  InvocationStateMachine::notifyMarkerCreatedReplaying)
              .add(
                  State.RESULT_NOTIFIED_REPLAYING,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED,
                  InvocationStateMachine::flushPreloadedVersionAndUpdateFromEventReplaying)
              .add(
                  State.RESULT_NOTIFIED_REPLAYING,
                  ExplicitEvent.NON_MATCHING_EVENT,
                  State.SKIPPED_NOTIFIED,
                  InvocationStateMachine::missingMarkerReplaying)
              .add(
                  State.SKIPPED_REPLAYING,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.SKIPPED_NOTIFIED,
                  InvocationStateMachine::notifySkippedReplaying);

  /** Represents a single invocation of version. */
  @VisibleForTesting
  class InvocationStateMachine
      extends EntityStateMachineInitialCommand<State, ExplicitEvent, InvocationStateMachine> {

    private final int minSupported;
    private final int maxSupported;

    private final Functions.Proc2<Integer, RuntimeException> resultCallback;

    InvocationStateMachine(
        int minSupported, int maxSupported, Functions.Proc2<Integer, RuntimeException> callback) {
      super(STATE_MACHINE_DEFINITION, VersionStateMachine.this.commandSink, stateMachineSink);
      this.minSupported = minSupported;
      this.maxSupported = maxSupported;
      this.resultCallback = Objects.requireNonNull(callback);
    }

    State getExecutionState() {
      return replaying.apply() ? State.REPLAYING : State.EXECUTING;
    }

    @Override
    public WorkflowStateMachines.HandleEventStatus handleEvent(
        HistoryEvent event, boolean hasNextEvent) {

      if (!VersionMarkerUtils.hasVersionMarkerStructure(event)) {
        // This event is not a version marker event, and it can't be handled as non-matching version
        // event.
        // So the best we can do with this state machine is to consider the event non-matching and
        // cancel the command produced by this state machine.
        // Then, give the event a chance to match with the next command.
        explicitEvent(ExplicitEvent.NON_MATCHING_EVENT);
        return WorkflowStateMachines.HandleEventStatus.NON_MATCHING_EVENT;
      }

      String expectedId = VersionMarkerUtils.getChangeId(event.getMarkerRecordedEventAttributes());
      if (Strings.isNullOrEmpty(expectedId)) {
        throw new IllegalStateException(
            "Version machine found in the history, but it doesn't contain a change id");
      }

      if (!changeId.equals(expectedId)) {
        // Do not call explicitEvent(ExplicitEvent.NON_MATCHING_EVENT) here as the event with
        // a different changeId can be followed by an event with our changeId.
        // An event will be handled as non-matching history event, it's fine.
        return WorkflowStateMachines.HandleEventStatus.NON_MATCHING_EVENT;
      }
      return super.handleEvent(event, hasNextEvent);
    }

    @Override
    public void handleWorkflowTaskStarted() {
      // Accounts for the case when there are no other events following the expected version marker,
      // so handleEvent
      // has no chance to trigger ExplicitEvent.NON_MATCHING_EVENT.
      // Also needed for subsequent getVersion calls that has no matching events and are not getting
      // explicit handleEvent calls with non-matching events, because they could be located after
      // the last command event is matched.
      if (getState() == State.RESULT_NOTIFIED_REPLAYING) {
        Preconditions.checkState(
            preloadedVersion == null, "preloadedVersion is expected to be flushed or never set");
        explicitEvent(ExplicitEvent.NON_MATCHING_EVENT);
      }
    }

    void createFakeCommand() {
      addCommand(StateMachineCommandUtils.RECORD_MARKER_FAKE_COMMAND);
    }

    private void validateVersionAndThrow(boolean preloaded) {
      Integer versionToUse = preloaded ? preloadedVersion : version;

      if (versionToUse == null) {
        throw new IllegalStateException((preloaded ? "preloaded " : "") + " version not set");
      }
      if (versionToUse < minSupported || versionToUse > maxSupported) {
        throw new UnsupportedVersion.UnsupportedVersionException(
            String.format(
                "Version %d of changeId %s is not supported. Supported v is between %d and %d.",
                versionToUse, changeId, minSupported, maxSupported));
      }
    }

    void notifyFromVersion(boolean preloaded) {
      Integer versionToUse = preloaded ? preloadedVersion : version;
      resultCallback.apply(versionToUse, null);
    }

    void notifyFromException(RuntimeException ex) {
      resultCallback.apply(null, ex);
    }

    void notifyFromVersionExecuting() {
      // the only case when we don't need to validate before notification because
      // we just initialized the version with maxVersion
      notifyFromVersion(false);
    }

    State createMarkerExecuting() {
      if (version != null) {
        // version for this change-id is already set and no maker should be created
        addCommand(StateMachineCommandUtils.RECORD_MARKER_FAKE_COMMAND);
        return State.SKIPPED;
      } else {
        version = maxSupported;
        RecordMarkerCommandAttributes markerAttributes =
            VersionMarkerUtils.createMarkerAttributes(changeId, version);
        addCommand(StateMachineCommandUtils.createRecordMarker(markerAttributes));
        return State.MARKER_COMMAND_CREATED;
      }
    }

    void notifySkippedExecuting() {
      cancelCommand();
      try {
        // It's an original execution, and we are in the skipping mode,
        // so we use a version from the original execution set by an earlier getVersion invocation
        final boolean usePreloadedVersion = false;
        validateVersionAndThrow(usePreloadedVersion);
        notifyFromVersion(usePreloadedVersion);
      } catch (RuntimeException ex) {
        notifyFromException(ex);
      }
    }

    void notifyMarkerCreatedReplaying() {
      try {
        // it's a replay and the version to return from the getVersion call should be preloaded from
        // the history
        final boolean usePreloadedVersion = true;
        validateVersionAndThrow(usePreloadedVersion);
        notifyFromVersion(usePreloadedVersion);
      } catch (RuntimeException ex) {
        notifyFromException(ex);
      }
    }

    State createMarkerReplaying() {
      createFakeCommand();
      if (preloadedVersion != null) {
        return State.MARKER_COMMAND_CREATED_REPLAYING;
      } else {
        return State.SKIPPED_REPLAYING;
      }
    }

    void flushPreloadedVersionAndUpdateFromEventReplaying() {
      Preconditions.checkState(
          preloadedVersion != null, "preloadedVersion is expected to be initialized");
      flushPreloadedVersionAndUpdateFromEvent(currentEvent);
    }

    void notifySkippedReplaying() {
      cancelCommand();
      if (version == null) {
        // We are in replay and in a skipped state, which means there was no matching marker in the
        // history
        // getVersion call wasn't here during the original execution, so we have to assume the
        // version to be DEFAULT_VERSION
        version = DEFAULT_VERSION;
      }
      try {
        // we are in the skipped state, so no preloaded event is expected from the history
        final boolean usePreloadedVersion = false;
        validateVersionAndThrow(usePreloadedVersion);
        notifyFromVersion(usePreloadedVersion);
      } catch (RuntimeException ex) {
        notifyFromException(ex);
      }
    }

    void missingMarkerReplaying() {
      if (preloadedVersion != null) {
        // 1. There is a version marker for the changeId, because there is a preloaded version.
        // 2. This version marker doesn't match this command, which means this version marker is
        // recorded later than this command. Otherwise, it would be flushed already by either a
        // matched event or earlier non-matched version marker.
        //
        // So, preloadedVersion != null means that this getVersion call is added before the original
        // getVersion call that caused the recording of the marker.
        throw new NonDeterministicException(
            "getVersion call before the existing version marker event. "
                + RETROACTIVE_ADDITION_ERROR_STRING);
      }
      cancelCommand();
    }
  }

  private void updateVersionFromEvent(HistoryEvent event) {
    if (version != null) {
      throw new NonDeterministicException(
          "Version is already set to " + version + ". " + RETROACTIVE_ADDITION_ERROR_STRING);
    }
    version = getVersionFromEvent(event);
  }

  private void preloadVersionFromEvent(HistoryEvent event) {
    if (version != null) {
      throw new NonDeterministicException(
          "Version is already set to " + version + ". " + RETROACTIVE_ADDITION_ERROR_STRING);
    }

    Preconditions.checkState(
        preloadedVersion == null,
        "Preloaded version is already set to %s. "
            + "Most likely the history has several version marker events for the same 'changeId'",
        preloadedVersion);

    preloadedVersion = getVersionFromEvent(event);
  }

  void flushPreloadedVersionAndUpdateFromEvent(HistoryEvent event) {
    updateVersionFromEvent(event);
    preloadedVersion = null;
  }

  /** Creates new VersionStateMachine */
  public static VersionStateMachine newInstance(
      String id,
      Functions.Func<Boolean> replaying,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new VersionStateMachine(id, replaying, commandSink, stateMachineSink);
  }

  private VersionStateMachine(
      String changeId,
      Functions.Func<Boolean> replaying,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    this.changeId = Objects.requireNonNull(changeId);
    this.replaying = Objects.requireNonNull(replaying);
    this.commandSink = Objects.requireNonNull(commandSink);
    this.stateMachineSink = stateMachineSink;
  }

  public State getVersion(
      int minSupported, int maxSupported, Functions.Proc2<Integer, RuntimeException> callback) {
    InvocationStateMachine ism = new InvocationStateMachine(minSupported, maxSupported, callback);
    ism.explicitEvent(ExplicitEvent.CHECK_EXECUTION_STATE);
    ism.explicitEvent(ExplicitEvent.SCHEDULE);
    return ism.getState();
  }

  public void handleNonMatchingEvent(HistoryEvent event) {
    flushPreloadedVersionAndUpdateFromEvent(event);
  }

  public void handleMarkersPreload(HistoryEvent event) {
    preloadVersionFromEvent(event);
  }

  private int getVersionFromEvent(HistoryEvent event) {
    Preconditions.checkArgument(
        VersionMarkerUtils.hasVersionMarkerStructure(event),
        "Expected a version marker event, got %s with '%s' marker name",
        event.getEventType(),
        event.getMarkerRecordedEventAttributes().getMarkerName());

    String eventChangeId = VersionMarkerUtils.getChangeId(event.getMarkerRecordedEventAttributes());
    Preconditions.checkArgument(
        this.changeId.equals(eventChangeId),
        "Got an event with an incorrect changeId, expected: %s, got %s",
        this.changeId,
        eventChangeId);

    Integer version = VersionMarkerUtils.getVersion(event.getMarkerRecordedEventAttributes());
    Preconditions.checkArgument(version != null, "Marker details missing required version key");

    return version;
  }
}
