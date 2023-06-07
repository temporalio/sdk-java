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

import com.google.common.base.Objects;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.internal.common.ProtocolType;

/**
 * This class represents an event that can cause a transition from one state to another.
 *
 * <p>There are three types of events that can cause state transitions:
 *
 * <ul>
 *   <li>Explicit event. The event that is reported through {@link
 *       StateMachine#handleExplicitEvent(Object, Object)}.
 *   <li>History event. This event is caused by processing an event recorded in the event history.
 *       It is reported through {@link StateMachine#handleHistoryEvent(EventType, Object)}.
 *   <li>Command event. This event is caused by reporting that a certain command is prepared to be
 *       sent as part of the workflow task response to the service. It is reported through {@link
 *       StateMachine#handleCommand(CommandType, Object)}.
 * </ul>
 *
 * An instance of TransitionEvent contains one of the above events.
 *
 * @param <ExplicitEvent> the type of the explicit event that the state machine supports.
 */
class TransitionEvent<ExplicitEvent> {

  public static final int EVENT_TYPE_PREFIX_LENGTH = "EVENT_TYPE_".length();
  public static final int COMMAND_TYPE_PREFIX_LENGTH = "COMMAND_TYPE_".length();

  final ExplicitEvent explicitEvent;
  final EventType historyEvent;
  final CommandType commandEvent;
  final ProtocolType messageEvent;

  public TransitionEvent(ExplicitEvent explicitEvent) {
    this.explicitEvent = explicitEvent;
    this.historyEvent = null;
    this.commandEvent = null;
    this.messageEvent = null;
  }

  public TransitionEvent(EventType historyEvent) {
    this.historyEvent = historyEvent;
    this.explicitEvent = null;
    this.commandEvent = null;
    this.messageEvent = null;
  }

  public TransitionEvent(ProtocolType messageEvent) {
    this.historyEvent = null;
    this.explicitEvent = null;
    this.commandEvent = null;
    this.messageEvent = messageEvent;
  }

  public TransitionEvent(CommandType commandEvent) {
    this.commandEvent = commandEvent;
    this.historyEvent = null;
    this.explicitEvent = null;
    this.messageEvent = null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TransitionEvent<?> that = (TransitionEvent<?>) o;
    return Objects.equal(explicitEvent, that.explicitEvent)
        && historyEvent == that.historyEvent
        && commandEvent == that.commandEvent
        && messageEvent == that.messageEvent;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(explicitEvent, historyEvent, commandEvent, messageEvent);
  }

  @Override
  public String toString() {
    if (explicitEvent != null) {
      return explicitEvent.toString();
    } else if (historyEvent != null) {
      return historyEvent.toString().substring(EVENT_TYPE_PREFIX_LENGTH);
    } else if (messageEvent != null) {
      return messageEvent.toString();
    }
    return commandEvent.toString().substring(COMMAND_TYPE_PREFIX_LENGTH);
  }
}
