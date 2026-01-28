package io.temporal.internal.common;

import io.temporal.api.protocol.v1.Message;
import io.temporal.internal.statemachines.UpdateProtocolCallback;

public class UpdateMessage {
  private final Message message;
  private final UpdateProtocolCallback callbacks;

  public UpdateMessage(Message message, UpdateProtocolCallback callbacks) {
    this.message = message;
    this.callbacks = callbacks;
  }

  public Message getMessage() {
    return message;
  }

  public UpdateProtocolCallback getCallbacks() {
    return this.callbacks;
  }
}
