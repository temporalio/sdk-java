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
import io.temporal.api.common.v1.Payloads;
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
    COMPLETED,
  }

  private final Functions.Func<Boolean> replaying;

  private final Functions.Proc1<UpdateMessage> updateHandle;
  private final Functions.Proc1<Message> sendHandle;

  private String protoInstanceID;
  private String requestMsgId;
  private long requestSeqID;
  private Request initialRequest;

  public static final StateMachineDefinition<State, ExplicitEvent, UpdateProtocolStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, UpdateProtocolStateMachine>newInstance(
                  "Update", State.NEW, State.COMPLETED)
              .add(
                  State.NEW,
                  ProtocolType.UPDATE_V1,
                  State.REQUEST_INITIATED,
                  UpdateProtocolStateMachine::triggerUpdate)
              .add(State.REQUEST_INITIATED, ExplicitEvent.ACCEPT, State.ACCEPTED)
              .add(State.REQUEST_INITIATED, ExplicitEvent.REJECT, State.COMPLETED)
              .add(State.ACCEPTED, ExplicitEvent.COMPLETE, State.COMPLETED);

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

    // TODO send ProtocolMessage command when server supports
    updateHandle.apply(updateMessage);
  }

  public void accept() {
    Acceptance acceptResponse =
        Acceptance.newBuilder()
            .setAcceptedRequestMessageId(requestMsgId)
            .setAcceptedRequestSequencingEventId(requestSeqID)
            .setAcceptedRequest(initialRequest)
            .build();

    sendHandle.apply(
        Message.newBuilder()
            .setId(requestMsgId + "/accept")
            .setProtocolInstanceId(protoInstanceID)
            .setBody(Any.pack(acceptResponse))
            .build());
    // TODO send ProtocolMessage command when server supports
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

    sendHandle.apply(
        Message.newBuilder()
            .setId(requestMsgId + "/reject")
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
    // TODO send ProtocolMessage command when server supports
    sendHandle.apply(
        Message.newBuilder()
            .setId(requestMsgId + "/complete")
            .setProtocolInstanceId(protoInstanceID)
            .setBody(Any.pack(outcomeResponse))
            .build());
    explicitEvent(ExplicitEvent.COMPLETE);
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
