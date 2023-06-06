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

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ProtocolMessageCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.update.v1.Acceptance;
import io.temporal.api.update.v1.Outcome;
import io.temporal.api.update.v1.Rejection;
import io.temporal.api.update.v1.Request;
import io.temporal.api.update.v1.Response;
import io.temporal.internal.common.ProtocolType;
import io.temporal.internal.common.UpdateMessage;
import io.temporal.workflow.Functions;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UpdateProtocolStateMachine
    extends EntityStateMachineInitialCommand<
        UpdateProtocolStateMachine.State,
        UpdateProtocolStateMachine.ExplicitEvent,
        UpdateProtocolStateMachine> {

  enum ExplicitEvent {
    REJECT,
    ACCEPT,
    COMPLETE,
  }

  enum State {
    NEW,
    REQUEST_INITIATED,
    ACCEPTED,
    ACCEPTED_COMMAND_CREATED,
    ACCEPTED_COMMAND_RECORDED,
    COMPLETED,
    COMPLETED_COMMAND_CREATED,
    COMPLETED_COMMAND_RECORDED,
    COMPLETED_IMMEDIATELY,
    COMPLETED_IMMEDIATELY_COMMAND_CREATED,
    COMPLETED_IMMEDIATELY_COMMAND_RECORDED
  }

  private static final Logger log = LoggerFactory.getLogger(UpdateProtocolStateMachine.class);

  private final Functions.Func<Boolean> replaying;

  private final Functions.Proc1<UpdateMessage> updateHandle;
  private final Functions.Proc1<Message> sendHandle;

  private String protoInstanceID;
  private String requestMsgId;
  private long requestSeqID;
  private Request initialRequest;
  private String messageId;

  public static final StateMachineDefinition<State, ExplicitEvent, UpdateProtocolStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, UpdateProtocolStateMachine>newInstance(
                  "Update", State.NEW, State.COMPLETED_COMMAND_RECORDED)
              .add(
                  State.NEW,
                  ProtocolType.UPDATE_V1,
                  State.REQUEST_INITIATED,
                  UpdateProtocolStateMachine::triggerUpdate)
              .add(
                  State.REQUEST_INITIATED,
                  ExplicitEvent.ACCEPT,
                  State.ACCEPTED,
                  UpdateProtocolStateMachine::sendCommandMessage)
              .add(
                  State.ACCEPTED,
                  CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE,
                  State.ACCEPTED_COMMAND_CREATED)
              .add(
                  State.ACCEPTED_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
                  State.ACCEPTED_COMMAND_RECORDED)
              .add(
                  State.ACCEPTED_COMMAND_RECORDED,
                  ExplicitEvent.COMPLETE,
                  State.COMPLETED,
                  UpdateProtocolStateMachine::sendCommandMessage)
              .add(
                  State.COMPLETED,
                  CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE,
                  State.COMPLETED_COMMAND_CREATED)
              .add(
                  State.COMPLETED_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
                  State.COMPLETED_COMMAND_RECORDED)
              // Handle the validation failure case
              .add(State.REQUEST_INITIATED, ExplicitEvent.REJECT, State.COMPLETED_COMMAND_RECORDED)
              // Handle an edge case when the update handle completes immediately. The state machine
              // should then expect
              // to see two protocol command messages back to back then two update events.
              .add(
                  State.ACCEPTED,
                  ExplicitEvent.COMPLETE,
                  State.COMPLETED_IMMEDIATELY,
                  UpdateProtocolStateMachine::sendCommandMessage)
              .add(
                  State.COMPLETED_IMMEDIATELY,
                  CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE,
                  State.COMPLETED_IMMEDIATELY_COMMAND_CREATED)
              .add(
                  State.COMPLETED_IMMEDIATELY_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE,
                  State.COMPLETED_IMMEDIATELY_COMMAND_RECORDED)
              .add(
                  State.COMPLETED_IMMEDIATELY_COMMAND_RECORDED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
                  State.COMPLETED_COMMAND_CREATED)
              // Handle an edge case when an update handle completes after it has sent the protocol
              // message command
              // but has not seen the corresponding event. This can happen if the update handle runs
              // a local activity
              .add(
                  State.ACCEPTED_COMMAND_CREATED,
                  ExplicitEvent.COMPLETE,
                  State.COMPLETED_IMMEDIATELY_COMMAND_CREATED,
                  UpdateProtocolStateMachine::sendCommandMessage);

  public static UpdateProtocolStateMachine newInstance(
      Functions.Func<Boolean> replaying,
      Functions.Proc1<UpdateMessage> updateHandle,
      Functions.Proc1<Message> sendHandle,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new UpdateProtocolStateMachine(
        replaying, updateHandle, sendHandle, commandSink, stateMachineSink);
  }

  private UpdateProtocolStateMachine(
      Functions.Func<Boolean> replaying,
      Functions.Proc1<UpdateMessage> updateHandle,
      Functions.Proc1<Message> sendHandle,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.replaying = replaying;
    this.updateHandle = updateHandle;
    this.sendHandle = sendHandle;
  }

  void triggerUpdate() {
    protoInstanceID = this.currentMessage.getProtocolInstanceId();
    requestMsgId = this.currentMessage.getId();
    requestSeqID = this.currentMessage.getEventId();
    try {
      initialRequest = this.currentMessage.getBody().unpack(Request.class);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Current message not an update:" + this.currentMessage);
    }
    UpdateMessage updateMessage =
        new UpdateMessage(this.currentMessage, new UpdateProtocolCallbackImpl());

    updateHandle.apply(updateMessage);
  }

  void sendCommandMessage() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE)
            .setProtocolMessageCommandAttributes(
                ProtocolMessageCommandAttributes.newBuilder().setMessageId(messageId))
            .build());
  }

  public void accept() {
    Acceptance acceptResponse =
        Acceptance.newBuilder()
            .setAcceptedRequestMessageId(requestMsgId)
            .setAcceptedRequestSequencingEventId(requestSeqID)
            .setAcceptedRequest(initialRequest)
            .build();

    messageId = requestMsgId + "/accept";
    sendHandle.apply(
        Message.newBuilder()
            .setId(messageId)
            .setProtocolInstanceId(protoInstanceID)
            .setBody(Any.pack(acceptResponse))
            .build());
    explicitEvent(ExplicitEvent.ACCEPT);
  }

  public void reject(Failure failure) {
    Rejection rejectResponse =
        Rejection.newBuilder()
            .setRejectedRequestMessageId(requestMsgId)
            .setRejectedRequestSequencingEventId(requestSeqID)
            .setRejectedRequest(initialRequest)
            .setFailure(failure)
            .build();

    String messageId = requestMsgId + "/reject";
    sendHandle.apply(
        Message.newBuilder()
            .setId(messageId)
            .setProtocolInstanceId(protoInstanceID)
            .setBody(Any.pack(rejectResponse))
            .build());
    explicitEvent(ExplicitEvent.REJECT);
  }

  public void complete(Optional<Payloads> payload, Failure failure) {
    Outcome.Builder outcome = Outcome.newBuilder();
    if (failure != null) {
      outcome = outcome.setFailure(failure);
    } else {
      outcome = outcome.setSuccess(payload.isPresent() ? payload.get() : null);
    }

    Response outcomeResponse =
        Response.newBuilder().setOutcome(outcome).setMeta(initialRequest.getMeta()).build();

    messageId = requestMsgId + "/complete";
    sendHandle.apply(
        Message.newBuilder()
            .setId(requestMsgId + "/complete")
            .setProtocolInstanceId(protoInstanceID)
            .setBody(Any.pack(outcomeResponse))
            .build());
    explicitEvent(ExplicitEvent.COMPLETE);
  }

  @Override
  public void handleMessage(Message message) {
    // The right sequence of failures on the server right now may lead to duplicate request messages
    // for the same protocolInstanceID being sent to the worker. To work around this ignore
    // subsequent
    // messages if we are not in the NEW state.
    if (getState() == State.NEW) {
      super.handleMessage(message);
    } else if (log.isWarnEnabled()) {
      log.warn(
          "Received duplicate update messages for protocol instance: "
              + message.getProtocolInstanceId());
    }
  }

  private class UpdateProtocolCallbackImpl implements UpdateProtocolCallback {

    @Override
    public void accept() {
      UpdateProtocolStateMachine.this.accept();
    }

    @Override
    public void reject(Failure failure) {
      UpdateProtocolStateMachine.this.reject(failure);
    }

    @Override
    public void complete(Optional<Payloads> result, Failure failure) {
      UpdateProtocolStateMachine.this.complete(result, failure);
    }

    @Override
    public boolean isReplaying() {
      return UpdateProtocolStateMachine.this.replaying.apply();
    }
  }
}
