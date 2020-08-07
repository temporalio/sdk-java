/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.statemachines;

import com.google.common.base.Objects;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;

class ActionOrEventType<Action> {

  public static final int EVENT_TYPE_PREFIX_LENGTH = "EVENT_TYPE_".length();
  public static final int COMMAND_TYPE_PREFIX_LENGTH = "COMMAND_TYPE_".length();

  final Action action;
  final EventType eventType;
  final CommandType commandType;

  public ActionOrEventType(Action action) {
    this.action = action;
    this.eventType = null;
    this.commandType = null;
  }

  public ActionOrEventType(EventType eventType) {
    this.eventType = eventType;
    this.action = null;
    this.commandType = null;
  }

  public ActionOrEventType(CommandType commandType) {
    this.commandType = commandType;
    this.eventType = null;
    this.action = null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActionOrEventType<?> that = (ActionOrEventType<?>) o;
    return Objects.equal(action, that.action)
        && eventType == that.eventType
        && commandType == that.commandType;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(action, eventType, commandType);
  }

  @Override
  public String toString() {
    if (action != null) {
      return action.toString();
    } else if (eventType != null) {
      return eventType.toString().substring(EVENT_TYPE_PREFIX_LENGTH);
    }
    return commandType.toString().substring(COMMAND_TYPE_PREFIX_LENGTH);
  }
}
