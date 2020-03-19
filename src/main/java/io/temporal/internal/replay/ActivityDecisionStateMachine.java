/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Modifications copyright (C) 2020 Temporal Technologies, Inc.
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

package io.temporal.internal.replay;

import io.temporal.proto.common.Decision;
import io.temporal.proto.common.HistoryEvent;
import io.temporal.proto.common.RequestCancelActivityTaskDecisionAttributes;
import io.temporal.proto.common.ScheduleActivityTaskDecisionAttributes;
import io.temporal.proto.enums.DecisionType;

final class ActivityDecisionStateMachine extends DecisionStateMachineBase {

  private ScheduleActivityTaskDecisionAttributes scheduleAttributes;

  public ActivityDecisionStateMachine(
      DecisionId id, ScheduleActivityTaskDecisionAttributes scheduleAttributes) {
    super(id);
    this.scheduleAttributes = scheduleAttributes;
  }

  /** Used for unit testing */
  ActivityDecisionStateMachine(
      DecisionId id,
      ScheduleActivityTaskDecisionAttributes scheduleAttributes,
      DecisionState state) {
    super(id, state);
    this.scheduleAttributes = scheduleAttributes;
  }

  @Override
  public Decision getDecision() {
    switch (state) {
      case CREATED:
        return createScheduleActivityTaskDecision();
      case CANCELED_AFTER_INITIATED:
        return createRequestCancelActivityTaskDecision();
      default:
        return null;
    }
  }

  @Override
  public void handleDecisionTaskStartedEvent() {
    switch (state) {
      case CANCELED_AFTER_INITIATED:
        stateHistory.add("handleDecisionTaskStartedEvent");
        state = DecisionState.CANCELLATION_DECISION_SENT;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleDecisionTaskStartedEvent();
    }
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    switch (state) {
      case CANCELLATION_DECISION_SENT:
        stateHistory.add("handleCancellationFailureEvent");
        state = DecisionState.INITIATED;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleCancellationFailureEvent(event);
    }
  }

  private Decision createRequestCancelActivityTaskDecision() {
    return Decision.newBuilder()
        .setRequestCancelActivityTaskDecisionAttributes(
            RequestCancelActivityTaskDecisionAttributes.newBuilder()
                .setActivityId(scheduleAttributes.getActivityId()))
        .setDecisionType(DecisionType.DecisionTypeRequestCancelActivityTask)
        .build();
  }

  private Decision createScheduleActivityTaskDecision() {
    return Decision.newBuilder()
        .setScheduleActivityTaskDecisionAttributes(scheduleAttributes)
        .setDecisionType(DecisionType.DecisionTypeScheduleActivityTask)
        .build();
  }
}
