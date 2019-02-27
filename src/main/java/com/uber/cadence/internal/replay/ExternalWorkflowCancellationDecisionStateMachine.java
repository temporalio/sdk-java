/*
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

package com.uber.cadence.internal.replay;

import com.uber.cadence.Decision;
import com.uber.cadence.DecisionType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.RequestCancelExternalWorkflowExecutionDecisionAttributes;

final class ExternalWorkflowCancellationDecisionStateMachine extends DecisionStateMachineBase {

  private RequestCancelExternalWorkflowExecutionDecisionAttributes attributes;

  ExternalWorkflowCancellationDecisionStateMachine(
      DecisionId decisionId, RequestCancelExternalWorkflowExecutionDecisionAttributes attributes) {
    super(decisionId);
    this.attributes = attributes;
  }

  @Override
  public Decision getDecision() {
    switch (state) {
      case CREATED:
        return createRequestCancelExternalWorkflowExecutionDecision();
      default:
        return null;
    }
  }

  @Override
  public boolean cancel(Runnable immediateCancellationCallback) {
    stateHistory.add("cancel");
    failStateTransition();
    return false;
  }

  @Override
  public void handleInitiatedEvent(HistoryEvent event) {
    stateHistory.add("handleInitiatedEvent");
    switch (state) {
      case DECISION_SENT:
        state = DecisionState.INITIATED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleInitiationFailedEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleStartedEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCompletionEvent() {
    stateHistory.add("handleCompletionEvent");
    switch (state) {
      case DECISION_SENT:
      case INITIATED:
        state = DecisionState.COMPLETED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleCancellationInitiatedEvent() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCancellationEvent() {
    throw new UnsupportedOperationException();
  }

  private Decision createRequestCancelExternalWorkflowExecutionDecision() {
    Decision decision = new Decision();
    decision.setRequestCancelExternalWorkflowExecutionDecisionAttributes(attributes);
    decision.setDecisionType(DecisionType.RequestCancelExternalWorkflowExecution);
    return decision;
  }
}
