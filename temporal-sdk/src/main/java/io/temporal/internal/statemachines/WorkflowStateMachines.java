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

import static io.temporal.api.enums.v1.CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE;
import static io.temporal.internal.common.WorkflowExecutionUtils.getEventTypeForCommand;
import static io.temporal.internal.common.WorkflowExecutionUtils.isCommandEvent;
import static io.temporal.internal.history.VersionMarkerUtils.*;
import static io.temporal.serviceclient.CheckedExceptionWrapper.unwrap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.protobuf.Any;
import io.temporal.api.command.v1.*;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.*;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.*;
import io.temporal.internal.history.LocalActivityMarkerUtils;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.sync.WorkflowThread;
import io.temporal.internal.worker.LocalActivityResult;
import io.temporal.serviceclient.Version;
import io.temporal.worker.NonDeterministicException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.Functions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkflowStateMachines {

  enum HandleEventStatus {
    OK,
    NON_MATCHING_EVENT
  }

  private static final Logger log = LoggerFactory.getLogger(WorkflowStateMachines.class);

  /** Initial set of SDK flags that will be set on all new workflow executions. */
  @VisibleForTesting
  public static List<SdkFlag> initialFlags =
      Collections.unmodifiableList(Arrays.asList(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION));

  /**
   * Keep track of the change versions that have been seen by the SDK. This is used to generate the
   * {@link VersionMarkerUtils#TEMPORAL_CHANGE_VERSION} search attribute. We use a LinkedHashMap to
   * ensure that the order of the change versions are preserved.
   */
  private final Map<String, Integer> changeVersions = new LinkedHashMap<>();

  /**
   * EventId of the WorkflowTaskStarted event of the Workflow Task that was picked up by a worker
   * and triggered a current replay or execution. It's expected to be the last event in the history
   * if we continue to execute the workflow.
   *
   * <p>For direct (legacy) queries, it may be:
   *
   * <ul>
   *   <li>0 if it's query a closed workflow execution
   *   <li>id of the last successfully completed Workflow Task if the workflow is not closed
   * </ul>
   *
   * <p>Set from the "outside" from the PollWorkflowTaskQueueResponse. Not modified by the SDK state
   * machines.
   */
  private long workflowTaskStartedEventId;

  /** EventId of the last WorkflowTaskStarted event handled by these state machines. */
  private long lastWFTStartedEventId;

  /** The Build ID used in the current WFT if already completed and set (may be null) */
  private String currentTaskBuildId;

  private long historySize;

  private boolean isContinueAsNewSuggested;

  /**
   * EventId of the last event seen by these state machines. Events earlier than this one will be
   * discarded.
   */
  private long lastHandledEventId;

  private final StatesMachinesCallback callbacks;

  /** Callback to send new commands to. */
  private final Functions.Proc1<CancellableCommand> commandSink;

  /**
   * currentRunId is used as seed by Workflow.newRandom and randomUUID. It allows to generate them
   * deterministically.
   */
  private String currentRunId;

  /** Used Workflow.newRandom and randomUUID together with currentRunId. */
  private long idCounter;

  /** Current workflow time. */
  private long currentTimeMillis = -1;

  private final Map<Long, EntityStateMachine> stateMachines = new HashMap<>();

  /** Key is the protocol instance id */
  private final Map<String, EntityStateMachine> protocolStateMachines = new HashMap<>();

  private final Queue<Message> messageOutbox = new ArrayDeque<>();

  private final Queue<CancellableCommand> commands = new ArrayDeque<>();

  /**
   * Commands generated by the currently processed workflow task. It is a queue as commands can be
   * added (due to marker based commands) while iterating over already added commands.
   */
  private final Queue<CancellableCommand> cancellableCommands = new ArrayDeque<>();

  /**
   * Is workflow executing new code or replaying from the history. The definition of replaying here
   * is that we are no longer replaying as soon as we see new events that have never been seen or
   * produced by the SDK.
   *
   * <p>Specifically, replay ends once we have seen any non-command event (IE: events that aren't a
   * result of something we produced in the SDK) on a WFT which has the final event in history
   * (meaning we are processing the most recent WFT and there are no more subsequent WFTs). WFT
   * Completed in this case does not count as a non-command event, because that will typically show
   * up as the first event in an incremental history, and we want to ignore it and its associated
   * commands since we "produced" them.
   *
   * <p>Note: that this flag ALWAYS flips to true for the time when we apply events from the server
   * even if the commands were created by an actual execution with replaying=false.
   */
  private boolean replaying;

  /** Used to ensure that event loop is not executed recursively. */
  private boolean eventLoopExecuting;

  /**
   * Used to avoid recursive calls to {@link #prepareCommands()}.
   *
   * <p>Such calls happen when sideEffects and localActivity markers are processed.
   */
  private boolean preparing;

  /** Key is mutable side effect id */
  private final Map<String, MutableSideEffectStateMachine> mutableSideEffects = new HashMap<>();

  /** Key is changeId */
  private final Map<String, VersionStateMachine> versions = new HashMap<>();

  /** Map of local activities by their id. */
  private final Map<String, LocalActivityStateMachine> localActivityMap = new HashMap<>();

  private List<ExecuteLocalActivityParameters> localActivityRequests = new ArrayList<>();

  private final Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink;
  private final Functions.Proc1<StateMachine> stateMachineSink;

  private final WFTBuffer wftBuffer = new WFTBuffer();

  private List<Message> messages = new ArrayList<>();

  /**
   * Set of accepted durably admitted updates by update id a "durably admitted" update is one with
   * an UPDATE_ADMITTED event.
   */
  private final Set<String> acceptedUpdates = new HashSet<>();

  private final SdkFlags flags;
  private final WorkflowImplementationOptions workflowImplOptions;
  @Nonnull private String lastSeenSdkName = "";
  @Nonnull private String lastSeenSdkVersion = "";

  /**
   * Track if the last event handled was a version marker for a getVersion call that was removed and
   * that event was excepted to be followed by an upsert search attribute for the
   * TemporalChangeVersion search attribute.
   */
  private boolean shouldSkipUpsertVersionSA = false;

  public WorkflowStateMachines(
      StatesMachinesCallback callbacks,
      GetSystemInfoResponse.Capabilities capabilities,
      WorkflowImplementationOptions workflowImplOptions) {
    this(callbacks, (stateMachine) -> {}, capabilities, workflowImplOptions);
  }

  @VisibleForTesting
  public WorkflowStateMachines(
      StatesMachinesCallback callbacks,
      Functions.Proc1<StateMachine> stateMachineSink,
      GetSystemInfoResponse.Capabilities capabilities,
      WorkflowImplementationOptions workflowImplOptions) {
    this.callbacks = Objects.requireNonNull(callbacks);
    this.commandSink = cancellableCommands::add;
    this.stateMachineSink = stateMachineSink;
    this.localActivityRequestSink = (request) -> localActivityRequests.add(request);
    this.flags = new SdkFlags(capabilities.getSdkMetadata(), this::isReplaying);
    this.workflowImplOptions = workflowImplOptions;
  }

  @VisibleForTesting
  public WorkflowStateMachines(
      StatesMachinesCallback callbacks, Functions.Proc1<StateMachine> stateMachineSink) {
    this.callbacks = Objects.requireNonNull(callbacks);
    this.commandSink = cancellableCommands::add;
    this.stateMachineSink = stateMachineSink;
    this.localActivityRequestSink = (request) -> localActivityRequests.add(request);
    this.flags = new SdkFlags(false, this::isReplaying);
    this.workflowImplOptions = WorkflowImplementationOptions.newBuilder().build();
  }

  // TODO revisit and potentially remove workflowTaskStartedEventId at all from the state machines.
  // The only place where it's used is WorkflowTaskStateMachine to understand that there will be
  // no completion event in the history.
  // This is tricky, because direct queries come with 0 as a magic value in this field if
  // execution is completed for example.
  // Most likely we can rework WorkflowTaskStateMachine to use only hasNext.
  /**
   * @param workflowTaskStartedEventId eventId of the workflowTask that was picked up by a worker
   *     and triggered an execution. Used in {@link WorkflowTaskStateMachine} only to understand
   *     that this workflow task will not have a matching closing event and needs to be executed.
   */
  public void setWorkflowStartedEventId(long workflowTaskStartedEventId) {
    this.workflowTaskStartedEventId = workflowTaskStartedEventId;
  }

  public void resetStartedEventId(long eventId) {
    // We must reset the last event we handled to be after the last WFT we really completed
    // + any command events (since the SDK "processed" those when it emitted the commands). This
    // is also equal to what we just processed in the speculative task, minus two, since we
    // would've just handled the most recent WFT started event, and we need to drop that & the
    // schedule event just before it.
    long resetLastHandledEventId = this.lastHandledEventId - 2;
    // We have to drop any state machines (which should only be one workflow task machine)
    // created when handling the speculative workflow task
    for (long i = this.lastHandledEventId; i > resetLastHandledEventId; i--) {
      stateMachines.remove(i);
    }
    this.lastWFTStartedEventId = eventId;
    this.lastHandledEventId = resetLastHandledEventId;
  }

  public long getLastWFTStartedEventId() {
    return lastWFTStartedEventId;
  }

  public long getCurrentWFTStartedEventId() {
    return workflowTaskStartedEventId;
  }

  public long getHistorySize() {
    return historySize;
  }

  @Nullable
  public String getCurrentTaskBuildId() {
    return currentTaskBuildId;
  }

  public boolean isContinueAsNewSuggested() {
    return isContinueAsNewSuggested;
  }

  public void setReplaying(boolean replaying) {
    this.replaying = replaying;
  }

  public void setMessages(List<Message> messages) {
    this.messages = new ArrayList<>(messages);
  }

  /**
   * Handle a single event from the workflow history.
   *
   * @param event event from the history.
   * @param hasNextEvent false if this is the last event in the history.
   */
  public void handleEvent(HistoryEvent event, boolean hasNextEvent) {
    long eventId = event.getEventId();
    if (eventId <= lastHandledEventId) {
      // already handled
      return;
    }
    Preconditions.checkState(
        eventId == lastHandledEventId + 1,
        "History is out of order. "
            + "There is a gap between the last event workflow state machine observed and currently handling event. "
            + "Last processed eventId: %s, handling eventId: %s",
        lastHandledEventId,
        eventId);

    lastHandledEventId = eventId;
    boolean readyToPeek = wftBuffer.addEvent(event, hasNextEvent);
    if (readyToPeek) {
      handleEventsBatch(wftBuffer.fetch(), hasNextEvent);
    }
  }

  /**
   * Handle an events batch for one workflow task. Events that are related to one workflow task
   * during replay should be prefetched and supplied in one batch.
   *
   * @param eventBatch events belong to one workflow task
   * @param hasNextBatch true if there are more events in the history follow this batch, false if
   *     this batch contains the last events of the history
   */
  private void handleEventsBatch(WFTBuffer.EventBatch eventBatch, boolean hasNextBatch) {
    List<HistoryEvent> events = eventBatch.getEvents();
    if (EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED.equals(events.get(0).getEventType())) {
      for (SdkFlag flag : initialFlags) {
        flags.tryUseSdkFlag(flag);
      }
    }

    if (eventBatch.getWorkflowTaskCompletedEvent().isPresent()) {
      for (HistoryEvent event : events) {
        handleSingleEventLookahead(event);
      }
    }

    for (Iterator<HistoryEvent> iterator = events.iterator(); iterator.hasNext(); ) {
      HistoryEvent event = iterator.next();

      // On replay the messages are available after the workflow task schedule event, so we
      // need to handle them before workflow task started event to maintain a consistent order.
      for (Message msg : this.takeLTE(event.getEventId() - 1)) {
        handleSingleMessage(msg);
      }

      try {
        boolean isLastTask =
            !hasNextBatch && !eventBatch.getWorkflowTaskCompletedEvent().isPresent();
        boolean hasNextEvent = iterator.hasNext() || hasNextBatch;
        handleSingleEvent(event, isLastTask, hasNextEvent);
      } catch (RuntimeException e) {
        throw createEventProcessingException(e, event);
      }

      for (Message msg : this.takeLTE(event.getEventId())) {
        handleSingleMessage(msg);
      }
    }
  }

  /** Handle an event when looking ahead at history during replay */
  private void handleSingleEventLookahead(HistoryEvent event) {
    EventType eventType = event.getEventType();
    switch (eventType) {
      case EVENT_TYPE_MARKER_RECORDED:
        try {
          preloadVersionMarker(event);
        } catch (RuntimeException e) {
          throw createEventProcessingException(e, event);
        }
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED:
        // Look ahead to infer protocol messages
        WorkflowExecutionUpdateAcceptedEventAttributes updateEvent =
            event.getWorkflowExecutionUpdateAcceptedEventAttributes();
        // If an EXECUTION_UPDATE_ACCEPTED event does not have an accepted request, then it
        // must be from an admitted update. This is the only way to infer an admitted update was
        // accepted.
        if (!updateEvent.hasAcceptedRequest()) {
          acceptedUpdates.add(updateEvent.getProtocolInstanceId());
        } else {
          messages.add(
              Message.newBuilder()
                  .setId(updateEvent.getAcceptedRequestMessageId())
                  .setProtocolInstanceId(updateEvent.getProtocolInstanceId())
                  .setEventId(updateEvent.getAcceptedRequestSequencingEventId())
                  .setBody(Any.pack(updateEvent.getAcceptedRequest()))
                  .build());
        }
        break;
      case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
        WorkflowTaskCompletedEventAttributes completedEvent =
            event.getWorkflowTaskCompletedEventAttributes();
        String maybeBuildId = completedEvent.getWorkerVersion().getBuildId();
        if (!maybeBuildId.isEmpty()) {
          currentTaskBuildId = maybeBuildId;
        }
        for (Integer flag : completedEvent.getSdkMetadata().getLangUsedFlagsList()) {
          SdkFlag sdkFlag = SdkFlag.getValue(flag);
          if (sdkFlag.equals(SdkFlag.UNKNOWN)) {
            throw new IllegalArgumentException("Unknown SDK flag:" + flag);
          }
          flags.setSdkFlag(sdkFlag);
        }
        if (!Strings.isNullOrEmpty(completedEvent.getSdkMetadata().getSdkName())) {
          lastSeenSdkName = completedEvent.getSdkMetadata().getSdkName();
        }
        if (!Strings.isNullOrEmpty(completedEvent.getSdkMetadata().getSdkVersion())) {
          lastSeenSdkVersion = completedEvent.getSdkMetadata().getSdkVersion();
        }
        // Remove any finished update protocol state machines. We can't remove them on an event like
        // other state machines because a rejected update produces no event in history.
        protocolStateMachines.entrySet().removeIf(entry -> entry.getValue().isFinalState());
        break;
      default:
        break;
    }
  }

  private List<Message> takeLTE(long eventId) {
    List<Message> m = new ArrayList<>();
    List<Message> remainingMessages = new ArrayList<>();
    for (Message msg : this.messages) {
      if (msg.getEventId() > eventId) {
        remainingMessages.add(msg);
      } else {
        m.add(msg);
      }
    }
    this.messages = remainingMessages;
    return m;
  }

  private RuntimeException createEventProcessingException(RuntimeException e, HistoryEvent event) {
    Throwable ex = unwrap(e);
    if (ex instanceof NonDeterministicException) {
      // just appending the message in front of an existing message, saving the original stacktrace
      NonDeterministicException modifiedException =
          new NonDeterministicException(
              createEventHandlingMessage(event)
                  + ". "
                  + ex.getMessage()
                  + ". "
                  + createShortCurrentStateMessagePostfix(),
              ex.getCause());
      modifiedException.setStackTrace(ex.getStackTrace());
      return modifiedException;
    } else {
      return new InternalWorkflowTaskException(
          createEventHandlingMessage(event) + ". " + createShortCurrentStateMessagePostfix(), ex);
    }
  }

  private void handleSingleMessage(Message message) {
    // Get or create protocol state machine based on Instance ID and protocolName
    EntityStateMachine stateMachine =
        protocolStateMachines.computeIfAbsent(
            message.getProtocolInstanceId(),
            (protocolInstance) -> {
              String protocolName = ProtocolUtils.getProtocol(message);
              Optional<ProtocolType> type = ProtocolType.get(protocolName);
              if (type.isPresent()) {
                switch (type.get()) {
                  case UPDATE_V1:
                    return UpdateProtocolStateMachine.newInstance(
                        this::isReplaying,
                        callbacks::update,
                        this::sendMessage,
                        commandSink,
                        stateMachineSink);
                  default:
                    throw new IllegalArgumentException("Unknown protocol type:" + protocolName);
                }
              }
              throw new IllegalArgumentException("Protocol type not specified:" + message);
            });
    stateMachine.handleMessage(message);
  }

  private void handleSingleEvent(HistoryEvent event, boolean lastTask, boolean hasNextEvent) {
    if (isCommandEvent(event)) {
      handleCommandEvent(event);
      return;
    }

    // We don't explicitly check if the event is a command event here because it's already handled
    // above.
    if (replaying
        && lastTask
        && event.getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
      replaying = false;
    }

    final OptionalLong initialCommandEventId = getInitialCommandEventId(event);
    if (!initialCommandEventId.isPresent()) {
      return;
    }

    EntityStateMachine c = stateMachines.get(initialCommandEventId.getAsLong());
    if (c != null) {
      c.handleEvent(event, hasNextEvent);
      if (c.isFinalState()) {
        stateMachines.remove(initialCommandEventId.getAsLong());
      }
    } else {
      handleNonStatefulEvent(event, hasNextEvent);
    }
  }

  /**
   * Handles command event. Command event is an event which is generated from a command emitted by a
   * past decision. Each command has a correspondent event. For example ScheduleActivityTaskCommand
   * is recorded to the history as ActivityTaskScheduledEvent.
   *
   * <p>Command events always follow WorkflowTaskCompletedEvent.
   *
   * <p>The handling consists from verifying that the next command in the commands queue matches the
   * event, command state machine is notified about the event and the command is removed from the
   * commands queue.
   */
  private void handleCommandEvent(HistoryEvent event) {
    if (handleLocalActivityMarker(event)) {
      return;
    }
    if (shouldSkipUpsertVersionSA) {
      if (handleNonMatchingUpsertSearchAttribute(event)) {
        shouldSkipUpsertVersionSA = false;
        return;
      } else {
        throw new NonDeterministicException("No command scheduled that corresponds to " + event);
      }
    }

    // Match event to the next command in the stateMachine queue.
    // After matching the command is notified about the event and is removed from the
    // queue.
    CancellableCommand matchingCommand = null;
    while (matchingCommand == null) {
      // handleVersionMarker can skip a marker event if the getVersion call was removed.
      // In this case we don't want to consume a command.
      // That's why peek is used instead of poll.
      CancellableCommand command = commands.peek();
      if (command == null) {
        if (handleNonMatchingVersionMarker(event)) {
          // this event is a version marker for removed getVersion call.
          // Handle the version marker as unmatched and return even if there is no commands to match
          // it against.
          shouldSkipUpsertVersionSA =
              VersionMarkerUtils.getUpsertVersionSA(event.getMarkerRecordedEventAttributes());
          return;
        } else {
          throw new NonDeterministicException("No command scheduled that corresponds to " + event);
        }
      }

      if (command.isCanceled()) {
        // Consume and skip the command
        commands.poll();
        continue;
      }

      // This checks if the next event is a version marker, but the next command is not a version
      // marker. This can happen if a getVersion call was removed.
      if (VersionMarkerUtils.hasVersionMarkerStructure(event)
          && !VersionMarkerUtils.hasVersionMarkerStructure(command.getCommand())) {
        if (handleNonMatchingVersionMarker(event)) {
          // this event is a version marker for removed getVersion call.
          // Handle the version marker as unmatched and return even if there is no commands to match
          // it against.
          shouldSkipUpsertVersionSA =
              VersionMarkerUtils.getUpsertVersionSA(event.getMarkerRecordedEventAttributes());
          return;
        } else {
          throw new NonDeterministicException(
              "Event "
                  + event.getEventId()
                  + " of type "
                  + event.getEventType()
                  + " does not"
                  + " match command type "
                  + command.getCommandType());
        }
      }
      // Note that handleEvent can cause a command cancellation in case of
      // 1. MutableSideEffect
      // 2. Version State Machine during replay cancels the command and enters SKIPPED state
      //    if it handled non-matching event.
      HandleEventStatus status = command.handleEvent(event, true);

      if (command.isCanceled()) {
        // Consume and skip the command
        commands.poll();
        continue;
      }

      switch (status) {
        case OK:
          // Consume the command
          commands.poll();
          matchingCommand = command;
          break;
        case NON_MATCHING_EVENT:
          if (handleNonMatchingVersionMarker(event)) {
            // this event is a version marker for removed getVersion call.
            // Handle the version marker as unmatched and return without consuming the command
            shouldSkipUpsertVersionSA =
                VersionMarkerUtils.getUpsertVersionSA(event.getMarkerRecordedEventAttributes());
            return;
          } else {
            throw new NonDeterministicException(
                "Event "
                    + event.getEventId()
                    + " of type "
                    + event.getEventType()
                    + " does not"
                    + " match command type "
                    + command.getCommandType());
          }
        default:
          throw new IllegalStateException(
              "Got " + status + " value from command.handleEvent which is not handled");
      }
    }

    validateCommand(matchingCommand.getCommand(), event);
    EntityStateMachine stateMachine = matchingCommand.getStateMachine();
    if (!stateMachine.isFinalState()) {
      stateMachines.put(event.getEventId(), stateMachine);
    }
    // Marker is the only command processing of which can cause workflow code execution
    // and generation of new state machines.
    if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED) {
      prepareCommands();
    }
  }

  private void preloadVersionMarker(HistoryEvent event) {
    if (VersionMarkerUtils.hasVersionMarkerStructure(event)) {
      String changeId = VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event);
      if (changeId == null) {
        // if we can't extract changeId, this event will later fail to match with anything
        // and the corresponded exception will be thrown
        return;
      }
      VersionStateMachine versionStateMachine =
          versions.computeIfAbsent(
              changeId,
              (idKey) ->
                  VersionStateMachine.newInstance(
                      changeId, this::isReplaying, commandSink, stateMachineSink));
      Integer version = versionStateMachine.handleMarkersPreload(event);
      if (versionStateMachine.isWriteVersionChangeSA()) {
        changeVersions.put(changeId, version);
      }
    }
  }

  private boolean handleNonMatchingVersionMarker(HistoryEvent event) {
    String changeId = VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event);
    if (changeId == null) {
      return false;
    }
    VersionStateMachine versionStateMachine = versions.get(changeId);
    Preconditions.checkNotNull(
        versionStateMachine,
        "versionStateMachine is expected to be initialized already by execution or preloading");
    versionStateMachine.handleNonMatchingEvent(event);
    return true;
  }

  private boolean handleNonMatchingUpsertSearchAttribute(HistoryEvent event) {
    if (event.hasUpsertWorkflowSearchAttributesEventAttributes()
        && event
            .getUpsertWorkflowSearchAttributesEventAttributes()
            .getSearchAttributes()
            .containsIndexedFields(TEMPORAL_CHANGE_VERSION.getName())) {
      return true;
    }
    return false;
  }

  public List<Command> takeCommands() {
    List<Command> result = new ArrayList<>(commands.size());
    for (CancellableCommand command : commands) {
      if (!command.isCanceled()) {
        result.add(command.getCommand());
      }
    }
    return result;
  }

  public void sendMessage(Message message) {
    checkEventLoopExecuting();
    if (!isReplaying()) {
      messageOutbox.add(message);
    }
  }

  public List<Message> takeMessages() {
    List<Message> result = new ArrayList<>(messageOutbox.size());
    result.addAll(messageOutbox);
    messageOutbox.clear();
    // Remove any finished update protocol state machines. We can't remove them on an event like
    // other state machines because a rejected update produces no event in history.
    protocolStateMachines.entrySet().removeIf(entry -> entry.getValue().isFinalState());
    return result;
  }

  /**
   * @return True if the SDK flag is supported in this workflow execution
   */
  public boolean tryUseSdkFlag(SdkFlag flag) {
    return flags.tryUseSdkFlag(flag);
  }

  /**
   * @return True if the SDK flag is set in the workflow execution
   */
  public boolean checkSdkFlag(SdkFlag flag) {
    return flags.checkSdkFlag(flag);
  }

  /**
   * @return Set of all new flags set since the last call
   */
  public EnumSet<SdkFlag> takeNewSdkFlags() {
    return flags.takeNewSdkFlags();
  }

  /**
   * @return If we need to write the SDK name upon WFT completion, return it
   */
  public String sdkNameToWrite() {
    if (!lastSeenSdkName.equals(Version.SDK_NAME)) {
      return Version.SDK_NAME;
    }
    return null;
  }

  /**
   * @return If we need to write the SDK version upon WFT completion, return it
   */
  public String sdkVersionToWrite() {
    if (!lastSeenSdkVersion.equals(Version.LIBRARY_VERSION)) {
      return Version.LIBRARY_VERSION;
    }
    return null;
  }

  private void prepareCommands() {
    if (preparing) {
      return;
    }
    preparing = true;
    try {
      prepareImpl();
    } finally {
      preparing = false;
    }
  }

  private void prepareImpl() {
    // handleCommand can lead to code execution because of SideEffect, MutableSideEffect or local
    // activity completion. And code execution can lead to creation of new commands and
    // cancellation of existing commands. That is the reason for using Queue as a data structure for
    // commands.
    while (true) {
      CancellableCommand command = cancellableCommands.poll();
      if (command == null) {
        break;
      }
      // handleCommand should be called even on canceled ones to support mutableSideEffect
      command.handleCommand(command.getCommandType());
      commands.add(command);
    }
  }

  /**
   * Local activity is different from all other entities. It doesn't schedule a marker command when
   * the {@link #scheduleLocalActivityTask(ExecuteLocalActivityParameters, Functions.Proc2)} is
   * called. The marker is scheduled only when activity completes through ({@link
   * #handleLocalActivityCompletion(LocalActivityResult)}). That's why the normal logic of {@link
   * #handleCommandEvent(HistoryEvent)}, which assumes that each event has a correspondent command
   * during replay, doesn't work. Instead, local activities are matched by their id using
   * localActivityMap.
   *
   * @return true if matched and false if normal event handling should continue.
   */
  private boolean handleLocalActivityMarker(HistoryEvent event) {
    if (!LocalActivityMarkerUtils.hasLocalActivityStructure(event)) {
      return false;
    }

    MarkerRecordedEventAttributes markerAttributes = event.getMarkerRecordedEventAttributes();
    String id = LocalActivityMarkerUtils.getActivityId(markerAttributes);
    LocalActivityStateMachine stateMachine = localActivityMap.remove(id);
    if (stateMachine == null) {
      String activityType = LocalActivityMarkerUtils.getActivityTypeName(markerAttributes);
      throw new NonDeterministicException(
          String.format(
              "Local activity of type %s is recorded in the history with id %s but was not expected by the execution",
              activityType, id));
    }
    // RESULT_NOTIFIED state means that there is outstanding command that has to be matched
    // using standard logic. So return false to let the handleCommand method to run its standard
    // logic.
    if (stateMachine.getState() == LocalActivityStateMachine.State.RESULT_NOTIFIED) {
      return false;
    }
    stateMachine.handleEvent(event, true);
    eventLoop();
    return true;
  }

  private void eventLoop() {
    if (eventLoopExecuting) {
      return;
    }
    eventLoopExecuting = true;
    try {
      callbacks.eventLoop();
    } finally {
      eventLoopExecuting = false;
    }
    prepareCommands();
  }

  private void handleNonStatefulEvent(HistoryEvent event, boolean hasNextEvent) {
    switch (event.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
        this.currentRunId =
            event.getWorkflowExecutionStartedEventAttributes().getOriginalExecutionRunId();
        callbacks.start(event);
        break;
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
        WorkflowTaskStateMachine c =
            WorkflowTaskStateMachine.newInstance(
                workflowTaskStartedEventId, new WorkflowTaskCommandsListener());
        c.handleEvent(event, hasNextEvent);
        stateMachines.put(event.getEventId(), c);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
        callbacks.signal(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        callbacks.cancel(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED:
        WorkflowExecutionUpdateAdmittedEventAttributes admittedEvent =
            event.getWorkflowExecutionUpdateAdmittedEventAttributes();
        Message msg =
            Message.newBuilder()
                .setId(admittedEvent.getRequest().getMeta().getUpdateId() + "/request")
                .setProtocolInstanceId(admittedEvent.getRequest().getMeta().getUpdateId())
                .setEventId(event.getEventId())
                .setBody(Any.pack(admittedEvent.getRequest()))
                .build();
        if (replaying && acceptedUpdates.remove(msg.getProtocolInstanceId()) || !replaying) {
          messages.add(msg);
        }
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
      case UNRECOGNIZED:
        break;
      default:
        throw new IllegalArgumentException("Unexpected event:" + event);
    }
  }

  private long setCurrentTimeMillis(long currentTimeMillis) {
    if (this.currentTimeMillis < currentTimeMillis) {
      this.currentTimeMillis = currentTimeMillis;
    }
    return this.currentTimeMillis;
  }

  public long getLastStartedEventId() {
    return lastWFTStartedEventId;
  }

  /**
   * @param attributes attributes used to schedule an activity
   * @param callback completion callback
   * @return an instance of ActivityCommands
   */
  public Functions.Proc scheduleActivityTask(
      ExecuteActivityParameters attributes, Functions.Proc2<Optional<Payloads>, Failure> callback) {
    checkEventLoopExecuting();
    ActivityStateMachine activityStateMachine =
        ActivityStateMachine.newInstance(
            attributes,
            (p, f) -> {
              Failure failure = f != null ? f.getFailure() : null;
              callback.apply(p, failure);

              if (f != null
                  && !f.isFromEvent()
                  && failure.hasCause()
                  && failure.getCause().hasCanceledFailureInfo()) {
                // If !f.isFromEvent(), we want to unblock the event loop as the promise got filled
                // and the workflow may make progress. If f.isFromEvent(), we need to delay event
                // loop triggering until WorkflowTaskStarted.
                eventLoop();
              }
            },
            commandSink,
            stateMachineSink);
    return activityStateMachine::cancel;
  }

  /**
   * Creates a new timer state machine
   *
   * @param attributes timer command attributes
   * @param metadata user provided metadata
   * @param completionCallback invoked when timer fires or reports cancellation. One of
   *     TimerFiredEvent, TimerCanceledEvent.
   * @return cancellation callback that should be invoked to initiate timer cancellation
   */
  public Functions.Proc newTimer(
      StartTimerCommandAttributes attributes,
      UserMetadata metadata,
      Functions.Proc1<HistoryEvent> completionCallback) {
    checkEventLoopExecuting();
    TimerStateMachine timer =
        TimerStateMachine.newInstance(
            attributes,
            metadata,
            (event) -> {
              completionCallback.apply(event);
              // Needed due to immediate cancellation
              if (event.getEventType() == EventType.EVENT_TYPE_TIMER_CANCELED) {
                eventLoop();
              }
            },
            commandSink,
            stateMachineSink);
    return timer::cancel;
  }

  /**
   * Creates a new child state machine
   *
   * @param parameters child workflow start command parameters
   * @param startedCallback callback that is notified about child start
   * @param completionCallback invoked when child reports completion or failure
   * @return cancellation callback that should be invoked to cancel the child
   */
  public Functions.Proc startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Functions.Proc2<WorkflowExecution, Exception> startedCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback) {
    checkEventLoopExecuting();
    StartChildWorkflowExecutionCommandAttributes attributes = parameters.getRequest().build();
    ChildWorkflowCancellationType cancellationType = parameters.getCancellationType();
    ChildWorkflowStateMachine child =
        ChildWorkflowStateMachine.newInstance(
            attributes,
            parameters.getMetadata(),
            startedCallback,
            completionCallback,
            commandSink,
            stateMachineSink);
    return () -> {
      if (cancellationType == ChildWorkflowCancellationType.ABANDON) {
        notifyChildCanceled(completionCallback);
        return;
      }
      // The only time child can be canceled directly is before its start command
      // was sent out to the service. After that RequestCancelExternal should be used.
      if (child.isCancellable()) {
        child.cancel();
        return;
      }
      if (!child.isFinalState()) {
        requestCancelExternalWorkflowExecution(
            RequestCancelExternalWorkflowExecutionCommandAttributes.newBuilder()
                .setWorkflowId(attributes.getWorkflowId())
                .setNamespace(attributes.getNamespace())
                .setChildWorkflowOnly(true)
                .build(),
            (r, e) -> { // TODO(maxim): Decide what to do if an error is passed to the callback.
              if (cancellationType == ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED) {
                notifyChildCanceled(completionCallback);
              }
            });
        if (cancellationType == ChildWorkflowCancellationType.TRY_CANCEL) {
          notifyChildCanceled(completionCallback);
        }
      }
    };
  }

  public Functions.Proc startNexusOperation(
      ScheduleNexusOperationCommandAttributes attributes,
      @Nullable UserMetadata metadata,
      Functions.Proc2<Optional<String>, Failure> startedCallback,
      Functions.Proc2<Optional<Payload>, Failure> completionCallback) {
    checkEventLoopExecuting();
    NexusOperationStateMachine operation =
        NexusOperationStateMachine.newInstance(
            attributes,
            metadata,
            startedCallback,
            completionCallback,
            commandSink,
            stateMachineSink);
    return () -> {
      if (operation.isCancellable()) {
        operation.cancel();
      }
      if (!operation.isFinalState()) {
        requestCancelNexusOperation(
            RequestCancelNexusOperationCommandAttributes.newBuilder()
                .setScheduledEventId(operation.getInitialCommandEventId())
                .build());
      }
    };
  }

  private void notifyChildCanceled(
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback) {
    CanceledFailure failure = new CanceledFailure("Child canceled");
    completionCallback.apply(Optional.empty(), failure);
    eventLoop();
  }

  /**
   * @param attributes
   * @param completionCallback invoked when signal delivery completes of fails. The following types
   */
  public Functions.Proc signalExternalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes attributes,
      Functions.Proc2<Void, Failure> completionCallback) {
    checkEventLoopExecuting();
    return SignalExternalStateMachine.newInstance(
        attributes, completionCallback, commandSink, stateMachineSink);
  }

  /**
   * @param attributes attributes to use to cancel external workflow
   * @param completionCallback one of ExternalWorkflowExecutionCancelRequestedEvent,
   */
  public void requestCancelExternalWorkflowExecution(
      RequestCancelExternalWorkflowExecutionCommandAttributes attributes,
      Functions.Proc2<Void, RuntimeException> completionCallback) {
    checkEventLoopExecuting();
    CancelExternalStateMachine.newInstance(
        attributes, completionCallback, commandSink, stateMachineSink);
  }

  /**
   * @param attributes attributes to use to cancel a nexus operation
   */
  public void requestCancelNexusOperation(RequestCancelNexusOperationCommandAttributes attributes) {
    checkEventLoopExecuting();
    CancelNexusOperationStateMachine.newInstance(attributes, commandSink, stateMachineSink);
  }

  public void upsertSearchAttributes(SearchAttributes attributes) {
    checkEventLoopExecuting();
    UpsertSearchAttributesStateMachine.newInstance(attributes, commandSink, stateMachineSink);
  }

  public void upsertMemo(Memo memo) {
    checkEventLoopExecuting();
    WorkflowPropertiesModifiedStateMachine.newInstance(
        ModifyWorkflowPropertiesCommandAttributes.newBuilder().setUpsertedMemo(memo).build(),
        commandSink,
        stateMachineSink);
  }

  public void completeWorkflow(Optional<Payloads> workflowOutput) {
    checkEventLoopExecuting();
    CompleteWorkflowStateMachine.newInstance(workflowOutput, commandSink, stateMachineSink);
  }

  public void failWorkflow(Failure failure) {
    checkEventLoopExecuting();
    FailWorkflowStateMachine.newInstance(failure, commandSink, stateMachineSink);
  }

  public void cancelWorkflow() {
    checkEventLoopExecuting();
    CancelWorkflowStateMachine.newInstance(
        CancelWorkflowExecutionCommandAttributes.getDefaultInstance(),
        commandSink,
        stateMachineSink);
  }

  public void continueAsNewWorkflow(ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    checkEventLoopExecuting();
    ContinueAsNewWorkflowStateMachine.newInstance(attributes, commandSink, stateMachineSink);
  }

  public boolean isReplaying() {
    return replaying;
  }

  public long currentTimeMillis() {
    return currentTimeMillis;
  }

  public UUID randomUUID() {
    checkEventLoopExecuting();
    String runId = currentRunId;
    if (runId == null) {
      throw new Error("null currentRunId");
    }
    String id = runId + ":" + idCounter++;
    byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
    return UUID.nameUUIDFromBytes(bytes);
  }

  public Random newRandom() {
    checkEventLoopExecuting();
    return new Random(randomUUID().getLeastSignificantBits());
  }

  public void sideEffect(
      Functions.Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback) {
    checkEventLoopExecuting();
    SideEffectStateMachine.newInstance(
        this::isReplaying,
        func,
        (payloads) -> {
          callback.apply(payloads);
          // callback unblocked sideEffect call. Give workflow code chance to make progress.
          eventLoop();
        },
        commandSink,
        stateMachineSink);
  }

  /**
   * @param id mutable side effect id
   * @param func given the value from the last marker returns value to store. If result is empty
   *     nothing is recorded into the history.
   * @param callback used to report result or failure
   */
  public void mutableSideEffect(
      String id,
      Functions.Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback) {
    checkEventLoopExecuting();
    MutableSideEffectStateMachine stateMachine =
        mutableSideEffects.computeIfAbsent(
            id,
            (idKey) ->
                MutableSideEffectStateMachine.newInstance(
                    idKey, this::isReplaying, commandSink, stateMachineSink));
    stateMachine.mutableSideEffect(
        func,
        (r) -> {
          callback.apply(r);
          // callback unblocked mutableSideEffect call. Give workflow code chance to make progress.
          eventLoop();
        },
        stateMachineSink);
  }

  public Integer getVersion(
      String changeId,
      int minSupported,
      int maxSupported,
      Functions.Proc2<Integer, RuntimeException> callback) {
    VersionStateMachine stateMachine =
        versions.computeIfAbsent(
            changeId,
            (idKey) ->
                VersionStateMachine.newInstance(
                    changeId, this::isReplaying, commandSink, stateMachineSink));
    return stateMachine.getVersion(
        minSupported,
        maxSupported,
        (version) -> {
          if (!workflowImplOptions.isEnableUpsertVersionSearchAttributes()) {
            return null;
          }
          if (version == null) {
            throw new IllegalStateException("Version is null");
          }
          SearchAttributes sa =
              VersionMarkerUtils.createVersionMarkerSearchAttributes(
                  changeId, version, changeVersions);
          changeVersions.put(changeId, version);
          if (sa.getIndexedFieldsMap().get(TEMPORAL_CHANGE_VERSION.getName()).getSerializedSize()
              >= CHANGE_VERSION_SEARCH_ATTRIBUTE_SIZE_LIMIT) {
            log.warn(
                "Serialized size of {} search attribute update would exceed the maximum value size. Skipping this upsert. Be aware that your visibility records will not include the following patch: {}",
                TEMPORAL_CHANGE_VERSION,
                VersionMarkerUtils.createChangeId(changeId, version));
            return null;
          }
          return sa;
        },
        (v, e) -> {
          callback.apply(v, e);
          // without this getVersion call will trigger the end of WFT,
          // instead we want to prepare subsequent commands and unblock the execution one more
          // time.
          eventLoop();
        });
  }

  public List<ExecuteLocalActivityParameters> takeLocalActivityRequests() {
    List<ExecuteLocalActivityParameters> result = localActivityRequests;
    localActivityRequests = new ArrayList<>();
    for (ExecuteLocalActivityParameters parameters : result) {
      LocalActivityStateMachine stateMachine = localActivityMap.get(parameters.getActivityId());
      stateMachine.markAsSent();
    }
    return result;
  }

  public void handleLocalActivityCompletion(LocalActivityResult laCompletion) {
    String activityId = laCompletion.getActivityId();
    LocalActivityStateMachine laStateMachine = localActivityMap.get(activityId);
    if (laStateMachine == null) {
      throw new IllegalStateException("Unknown local activity: " + activityId);
    }
    laStateMachine.handleCompletion(laCompletion);
    prepareCommands();
  }

  public Functions.Proc scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, LocalActivityCallback.LocalActivityFailedException>
          callback) {
    checkEventLoopExecuting();
    String activityId = parameters.getActivityId();
    if (Strings.isNullOrEmpty(activityId)) {
      throw new IllegalArgumentException("Missing activityId: " + activityId);
    }
    if (localActivityMap.containsKey(activityId)) {
      throw new IllegalArgumentException("Duplicated local activity id: " + activityId);
    }
    LocalActivityStateMachine commands =
        LocalActivityStateMachine.newInstance(
            this::isReplaying,
            this::setCurrentTimeMillis,
            parameters,
            (r, e) -> {
              callback.apply(r, e);
              // callback unblocked local activity call. Give workflow code chance to make progress.
              eventLoop();
            },
            localActivityRequestSink,
            commandSink,
            stateMachineSink,
            currentTimeMillis);
    localActivityMap.put(activityId, commands);
    return commands::cancel;
  }

  /** Validates that command matches the event during replay. */
  private void validateCommand(Command command, HistoryEvent event) {
    // ProtocolMessageCommand is different from other commands because it can be associated with
    // multiple types of events
    // TODO(#1781) Validate protocol message is expected type.
    if (command.getCommandType() == COMMAND_TYPE_PROTOCOL_MESSAGE) {
      ProtocolMessageCommandAttributes commandAttributes =
          command.getProtocolMessageCommandAttributes();
      switch (event.getEventType()) {
        case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED:
          assertMatch(
              command,
              event,
              "messageType",
              true,
              commandAttributes.getMessageId().endsWith("accept"));
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED:
          assertMatch(
              command,
              event,
              "messageType",
              true,
              commandAttributes.getMessageId().endsWith("reject"));
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED:
          assertMatch(
              command,
              event,
              "messageType",
              true,
              commandAttributes.getMessageId().endsWith("complete"));
          break;
        default:
          throw new IllegalArgumentException("Unexpected event type: " + event.getEventType());
      }
      return;
    }
    assertMatch(
        command,
        event,
        "eventType",
        getEventTypeForCommand(command.getCommandType()),
        event.getEventType());
    switch (command.getCommandType()) {
      case COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK:
        {
          ScheduleActivityTaskCommandAttributes commandAttributes =
              command.getScheduleActivityTaskCommandAttributes();
          ActivityTaskScheduledEventAttributes eventAttributes =
              event.getActivityTaskScheduledEventAttributes();
          assertMatch(
              command,
              event,
              "activityId",
              commandAttributes.getActivityId(),
              eventAttributes.getActivityId());
          assertMatch(
              command,
              event,
              "activityType",
              commandAttributes.getActivityType(),
              eventAttributes.getActivityType());
        }
        break;
      case COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION:
        {
          StartChildWorkflowExecutionCommandAttributes commandAttributes =
              command.getStartChildWorkflowExecutionCommandAttributes();
          StartChildWorkflowExecutionInitiatedEventAttributes eventAttributes =
              event.getStartChildWorkflowExecutionInitiatedEventAttributes();
          assertMatch(
              command,
              event,
              "workflowId",
              commandAttributes.getWorkflowId(),
              eventAttributes.getWorkflowId());
          assertMatch(
              command,
              event,
              "workflowType",
              commandAttributes.getWorkflowType(),
              eventAttributes.getWorkflowType());
        }
        break;
      case COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK:
      case COMMAND_TYPE_START_TIMER:
        {
          StartTimerCommandAttributes commandAttributes = command.getStartTimerCommandAttributes();
          TimerStartedEventAttributes eventAttributes = event.getTimerStartedEventAttributes();
          assertMatch(
              command,
              event,
              "timerId",
              commandAttributes.getTimerId(),
              eventAttributes.getTimerId());
        }
        break;
      case COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION:
        {
          ScheduleNexusOperationCommandAttributes commandAttributes =
              command.getScheduleNexusOperationCommandAttributes();
          NexusOperationScheduledEventAttributes eventAttributes =
              event.getNexusOperationScheduledEventAttributes();
          assertMatch(
              command,
              event,
              "operation",
              commandAttributes.getOperation(),
              eventAttributes.getOperation());
          assertMatch(
              command,
              event,
              "service",
              commandAttributes.getService(),
              eventAttributes.getService());
        }
        break;
      case COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION:
        {
          RequestCancelNexusOperationCommandAttributes commandAttributes =
              command.getRequestCancelNexusOperationCommandAttributes();
          NexusOperationCancelRequestedEventAttributes eventAttributes =
              event.getNexusOperationCancelRequestedEventAttributes();
          assertMatch(
              command,
              event,
              "scheduleEventId",
              commandAttributes.getScheduledEventId(),
              eventAttributes.getScheduledEventId());
        }
        break;
      case COMMAND_TYPE_CANCEL_TIMER:
      case COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_RECORD_MARKER:
      case COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
      case COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_PROTOCOL_MESSAGE:
      case COMMAND_TYPE_MODIFY_WORKFLOW_PROPERTIES:
        break;
      case UNRECOGNIZED:
      case COMMAND_TYPE_UNSPECIFIED:
        throw new IllegalArgumentException("Unexpected command type: " + command.getCommandType());
    }
  }

  private void assertMatch(
      Command command, HistoryEvent event, String checkType, Object expected, Object actual) {
    if (!expected.equals(actual)) {
      String message =
          String.format(
              "Command %s doesn't match event %s with EventId=%s on check %s "
                  + "with an expected value '%s' and an actual value '%s'",
              command.getCommandType(),
              event.getEventType(),
              event.getEventId(),
              checkType,
              expected,
              actual);
      throw new NonDeterministicException(message);
    }
  }

  private class WorkflowTaskCommandsListener implements WorkflowTaskStateMachine.Listener {
    @Override
    public void workflowTaskStarted(
        long startedEventId,
        long currentTimeMillis,
        boolean nonProcessedWorkflowTask,
        long historySize,
        boolean isContinueAsNewSuggested) {
      setCurrentTimeMillis(currentTimeMillis);
      for (CancellableCommand cancellableCommand : commands) {
        cancellableCommand.handleWorkflowTaskStarted();
      }
      // Give local activities a chance to recreate their requests if they were lost due
      // to the last workflow task failure. The loss could happen only the last workflow task
      // was forcibly created by setting forceCreate on RespondWorkflowTaskCompletedRequest.
      if (nonProcessedWorkflowTask) {
        for (LocalActivityStateMachine value : localActivityMap.values()) {
          value.nonReplayWorkflowTaskStarted();
        }
      }
      WorkflowStateMachines.this.lastWFTStartedEventId = startedEventId;
      WorkflowStateMachines.this.historySize = historySize;
      WorkflowStateMachines.this.isContinueAsNewSuggested = isContinueAsNewSuggested;

      eventLoop();
    }

    @Override
    public void updateRunId(String currentRunId) {
      WorkflowStateMachines.this.currentRunId = currentRunId;
    }
  }

  /**
   * Extracts the eventId of the "initial command" for the given event.
   *
   * <p>The "initial command" is the event which started a group of related events:
   * ActivityTaskScheduled, TimerStarted, and so on; for events which are not part of a group, the
   * event's own eventId is returned. If the event has an unknown type but is marked as ignorable,
   * then {@link OptionalLong#empty()} is returned instead.
   *
   * @return the eventId of the initial command, or {@link OptionalLong#empty()}
   */
  private OptionalLong getInitialCommandEventId(HistoryEvent event) {
    switch (event.getEventType()) {
      case EVENT_TYPE_ACTIVITY_TASK_STARTED:
        return OptionalLong.of(event.getActivityTaskStartedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
        return OptionalLong.of(
            event.getActivityTaskCompletedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_ACTIVITY_TASK_FAILED:
        return OptionalLong.of(event.getActivityTaskFailedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
        return OptionalLong.of(
            event.getActivityTaskTimedOutEventAttributes().getScheduledEventId());
      case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
        return OptionalLong.of(
            event.getActivityTaskCancelRequestedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
        return OptionalLong.of(
            event.getActivityTaskCanceledEventAttributes().getScheduledEventId());
      case EVENT_TYPE_TIMER_FIRED:
        return OptionalLong.of(event.getTimerFiredEventAttributes().getStartedEventId());
      case EVENT_TYPE_TIMER_CANCELED:
        return OptionalLong.of(event.getTimerCanceledEventAttributes().getStartedEventId());
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        return OptionalLong.of(
            event
                .getRequestCancelExternalWorkflowExecutionFailedEventAttributes()
                .getInitiatedEventId());
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        return OptionalLong.of(
            event
                .getExternalWorkflowExecutionCancelRequestedEventAttributes()
                .getInitiatedEventId());
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
        return OptionalLong.of(
            event.getStartChildWorkflowExecutionFailedEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
        return OptionalLong.of(
            event.getChildWorkflowExecutionStartedEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
        return OptionalLong.of(
            event.getChildWorkflowExecutionCompletedEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
        return OptionalLong.of(
            event.getChildWorkflowExecutionFailedEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
        return OptionalLong.of(
            event.getChildWorkflowExecutionCanceledEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
        return OptionalLong.of(
            event.getChildWorkflowExecutionTimedOutEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
        return OptionalLong.of(
            event.getChildWorkflowExecutionTerminatedEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        return OptionalLong.of(
            event.getSignalExternalWorkflowExecutionFailedEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED:
        return OptionalLong.of(
            event.getExternalWorkflowExecutionSignaledEventAttributes().getInitiatedEventId());
      case EVENT_TYPE_WORKFLOW_TASK_STARTED:
        return OptionalLong.of(event.getWorkflowTaskStartedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
        return OptionalLong.of(
            event.getWorkflowTaskCompletedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
        return OptionalLong.of(
            event.getWorkflowTaskTimedOutEventAttributes().getScheduledEventId());
      case EVENT_TYPE_WORKFLOW_TASK_FAILED:
        return OptionalLong.of(event.getWorkflowTaskFailedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_NEXUS_OPERATION_STARTED:
        return OptionalLong.of(
            event.getNexusOperationStartedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_NEXUS_OPERATION_COMPLETED:
        return OptionalLong.of(
            event.getNexusOperationCompletedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_NEXUS_OPERATION_FAILED:
        return OptionalLong.of(
            event.getNexusOperationFailedEventAttributes().getScheduledEventId());
      case EVENT_TYPE_NEXUS_OPERATION_CANCELED:
        return OptionalLong.of(
            event.getNexusOperationCanceledEventAttributes().getScheduledEventId());
      case EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT:
        return OptionalLong.of(
            event.getNexusOperationTimedOutEventAttributes().getScheduledEventId());
      case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
      case EVENT_TYPE_TIMER_STARTED:
      case EVENT_TYPE_MARKER_RECORDED:
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
      case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED:
      case EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED:
      case EVENT_TYPE_NEXUS_OPERATION_SCHEDULED:
      case EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED:
        return OptionalLong.of(event.getEventId());

      default:
        if (event.getWorkerMayIgnore()) {
          return OptionalLong.empty();
        }
        throw new IllegalArgumentException("Unexpected event type: " + event.getEventType());
    }
  }

  /**
   * Workflow code executes only while event loop is running. So operations that can be invoked from
   * the workflow have to satisfy this condition.
   */
  private void checkEventLoopExecuting() {
    if (!eventLoopExecuting) {
      // this call doesn't yield or await, because the await function returns true,
      // but it checks if the workflow thread needs to be destroyed
      WorkflowThread.await("kill workflow thread if destroy requested", () -> true);
      throw new IllegalStateException("Operation allowed only while eventLoop is running");
    }
  }

  private String createEventHandlingMessage(HistoryEvent event) {
    return "Failure handling event "
        + event.getEventId()
        + " of type '"
        + event.getEventType()
        + "' "
        + (this.isReplaying() ? "during replay" : "during execution");
  }

  private String createShortCurrentStateMessagePostfix() {
    return String.format(
        "{WorkflowTaskStartedEventId=%s, CurrentStartedEventId=%s}",
        this.workflowTaskStartedEventId, this.lastWFTStartedEventId);
  }
}
