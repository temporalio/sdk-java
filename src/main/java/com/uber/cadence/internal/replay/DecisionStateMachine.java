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

import com.uber.cadence.HistoryEvent;

interface DecisionStateMachine {

  com.uber.cadence.Decision getDecision();

  /** @return true if produced a decision */
  boolean cancel(Runnable immediateCancellationCallback);

  void handleStartedEvent(HistoryEvent event);

  void handleCancellationInitiatedEvent();

  void handleCancellationEvent();

  void handleCancellationFailureEvent(HistoryEvent event);

  void handleCompletionEvent();

  void handleInitiationFailedEvent(HistoryEvent event);

  void handleInitiatedEvent(HistoryEvent event);

  void handleDecisionTaskStartedEvent();

  DecisionState getState();

  boolean isDone();

  DecisionId getId();
}
