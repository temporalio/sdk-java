package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.NexusOperationFailureInfo;
import io.temporal.api.history.v1.*;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.workflow.Functions;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * NexusOperationStateMachine manages a nexus operation.
 *
 * <p>Note: A cancellation request is managed by {@link CancelNexusOperationStateMachine}
 */
final class NexusOperationStateMachine
    extends EntityStateMachineInitialCommand<
        NexusOperationStateMachine.State,
        NexusOperationStateMachine.ExplicitEvent,
        NexusOperationStateMachine> {
  private static final String JAVA_SDK = "JavaSDK";
  private static final String NEXUS_OPERATION_CANCELED_MESSAGE = "Nexus operation canceled";

  private ScheduleNexusOperationCommandAttributes scheduleAttributes;
  private UserMetadata metadata;
  private final Functions.Proc2<Optional<String>, Failure> startedCallback;
  private boolean async = false;

  private final Functions.Proc2<Optional<Payload>, Failure> completionCallback;
  private final String endpoint;
  private final String service;
  private final String operation;

  public boolean isAsync() {
    return async;
  }

  public boolean isCancellable() {
    return State.SCHEDULE_COMMAND_CREATED == getState();
  }

  public void cancel() {
    if (!isFinalState()) {
      explicitEvent(ExplicitEvent.CANCEL);
    }
  }

  enum ExplicitEvent {
    SCHEDULE,
    CANCEL
  }

  enum State {
    CREATED,
    SCHEDULE_COMMAND_CREATED,
    SCHEDULED_EVENT_RECORDED,
    STARTED,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELED,
  }

  public static final StateMachineDefinition<
          NexusOperationStateMachine.State,
          NexusOperationStateMachine.ExplicitEvent,
          NexusOperationStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<NexusOperationStateMachine.State, NexusOperationStateMachine.ExplicitEvent,
                  NexusOperationStateMachine>
                  newInstance(
                      "NexusOperation",
                      State.CREATED,
                      State.COMPLETED,
                      State.FAILED,
                      State.TIMED_OUT,
                      State.CANCELED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.SCHEDULE_COMMAND_CREATED,
                  NexusOperationStateMachine::createScheduleNexusTaskCommand)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION,
                  State.SCHEDULE_COMMAND_CREATED)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
                  State.SCHEDULED_EVENT_RECORDED,
                  NexusOperationStateMachine::setInitialCommandEventId)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
                  State.CANCELED,
                  NexusOperationStateMachine::cancelNexusOperationCommand)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
                  State.COMPLETED,
                  NexusOperationStateMachine::notifyCompleted)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
                  State.FAILED,
                  NexusOperationStateMachine::notifyFailed)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
                  State.CANCELED,
                  NexusOperationStateMachine::notifyCanceled)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
                  State.TIMED_OUT,
                  NexusOperationStateMachine::notifyTimedOut)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
                  State.STARTED,
                  NexusOperationStateMachine::notifyStarted)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
                  State.COMPLETED,
                  NexusOperationStateMachine::notifyCompleted)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
                  State.FAILED,
                  NexusOperationStateMachine::notifyFailed)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
                  State.CANCELED,
                  NexusOperationStateMachine::notifyCanceled)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
                  State.TIMED_OUT,
                  NexusOperationStateMachine::notifyTimedOut);

  private void cancelNexusOperationCommand() {
    cancelCommand();
    Failure cause =
        Failure.newBuilder()
            .setSource(JAVA_SDK)
            .setMessage("operation canceled before it was started")
            .setCanceledFailureInfo(CanceledFailureInfo.getDefaultInstance())
            .build();
    Failure failure = createCancelNexusOperationFailure(cause);
    startedCallback.apply(Optional.empty(), failure);
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyStarted() {
    async = true;
    String operationToken =
        currentEvent.getNexusOperationStartedEventAttributes().getOperationToken();
    // TODO(#2423) Remove support for operationId
    String operationId = currentEvent.getNexusOperationStartedEventAttributes().getOperationId();
    startedCallback.apply(
        Optional.of(operationToken.isEmpty() ? operationId : operationToken), null);
  }

  private void notifyCompleted() {
    NexusOperationCompletedEventAttributes attributes =
        currentEvent.getNexusOperationCompletedEventAttributes();
    if (!async) {
      startedCallback.apply(Optional.empty(), null);
    }
    completionCallback.apply(Optional.of(attributes.getResult()), null);
  }

  private void notifyFailed() {
    NexusOperationFailedEventAttributes attributes =
        currentEvent.getNexusOperationFailedEventAttributes();
    if (!async) {
      startedCallback.apply(Optional.empty(), attributes.getFailure());
    }
    completionCallback.apply(Optional.empty(), attributes.getFailure());
  }

  private void notifyCanceled() {
    NexusOperationCanceledEventAttributes attributes =
        currentEvent.getNexusOperationCanceledEventAttributes();
    if (!async) {
      startedCallback.apply(Optional.empty(), attributes.getFailure());
    }
    completionCallback.apply(Optional.empty(), attributes.getFailure());
  }

  private void notifyTimedOut() {
    NexusOperationTimedOutEventAttributes attributes =
        currentEvent.getNexusOperationTimedOutEventAttributes();
    if (!async) {
      startedCallback.apply(Optional.empty(), attributes.getFailure());
    }
    completionCallback.apply(Optional.empty(), attributes.getFailure());
  }

  /**
   * @param attributes attributes used to schedule the nexus operation
   * @param startedCallback invoked when the Nexus operation start
   * @param completionCallback invoked when Nexus operation completes
   * @param commandSink sink to send commands
   * @return an instance of NexusOperationStateMachine
   */
  public static NexusOperationStateMachine newInstance(
      ScheduleNexusOperationCommandAttributes attributes,
      @Nullable UserMetadata metadata,
      Functions.Proc2<Optional<String>, Failure> startedCallback,
      Functions.Proc2<Optional<Payload>, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new NexusOperationStateMachine(
        attributes, metadata, startedCallback, completionCallback, commandSink, stateMachineSink);
  }

  private NexusOperationStateMachine(
      ScheduleNexusOperationCommandAttributes attributes,
      @Nullable UserMetadata metadata,
      Functions.Proc2<Optional<String>, Failure> startedCallback,
      Functions.Proc2<Optional<Payload>, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.scheduleAttributes = attributes;
    this.metadata = metadata;
    this.operation = attributes.getOperation();
    this.service = attributes.getService();
    this.endpoint = attributes.getEndpoint();
    this.startedCallback = startedCallback;
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  public void createScheduleNexusTaskCommand() {
    Command.Builder command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION)
            .setScheduleNexusOperationCommandAttributes(scheduleAttributes);
    if (metadata != null) {
      command.setUserMetadata(metadata);
    }
    addCommand(command.build());
    // avoiding retaining large input for the duration of the operation
    scheduleAttributes = null;
    metadata = null;
  }

  public Failure createCancelNexusOperationFailure(Failure cause) {
    if (cause == null) {
      cause =
          Failure.newBuilder()
              .setSource(JAVA_SDK)
              .setMessage("operation canceled")
              .setCanceledFailureInfo(CanceledFailureInfo.getDefaultInstance())
              .build();
    }
    NexusOperationFailureInfo nexusFailureInfo =
        NexusOperationFailureInfo.newBuilder()
            .setEndpoint(endpoint)
            .setService(service)
            .setOperation(operation)
            .setScheduledEventId(getInitialCommandEventId())
            .build();
    return Failure.newBuilder()
        .setNexusOperationExecutionFailureInfo(nexusFailureInfo)
        .setCause(cause)
        .setMessage(NEXUS_OPERATION_CANCELED_MESSAGE)
        .build();
  }
}
