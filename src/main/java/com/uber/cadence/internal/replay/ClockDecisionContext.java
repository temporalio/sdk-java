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
import com.uber.cadence.StartTimerDecisionAttributes;
import com.uber.cadence.TimerCanceledEventAttributes;
import com.uber.cadence.TimerFiredEventAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Clock that must be used inside workflow definition code to ensure replay determinism. */
final class ClockDecisionContext {

  private final class TimerCancellationHandler implements Consumer<Exception> {

    private final String timerId;

    TimerCancellationHandler(String timerId) {
      this.timerId = timerId;
    }

    @Override
    public void accept(Exception reason) {
      decisions.cancelTimer(timerId, () -> timerCancelled(timerId, reason));
    }
  }

  private final DecisionsHelper decisions;

  private final Map<String, OpenRequestInfo<?, Long>> scheduledTimers = new HashMap<>();

  private long replayCurrentTimeMilliseconds;

  private boolean replaying = true;

  ClockDecisionContext(DecisionsHelper decisions) {
    this.decisions = decisions;
  }

  long currentTimeMillis() {
    return replayCurrentTimeMilliseconds;
  }

  void setReplayCurrentTimeMilliseconds(long replayCurrentTimeMilliseconds) {
    this.replayCurrentTimeMilliseconds = replayCurrentTimeMilliseconds;
  }

  boolean isReplaying() {
    return replaying;
  }

  Consumer<Exception> createTimer(long delaySeconds, Consumer<Exception> callback) {
    if (delaySeconds < 0) {
      throw new IllegalArgumentException("Negative delaySeconds: " + delaySeconds);
    }
    if (delaySeconds == 0) {
      callback.accept(null);
      return null;
    }
    long firingTime = currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
    final OpenRequestInfo<?, Long> context = new OpenRequestInfo<>(firingTime);
    final StartTimerDecisionAttributes timer = new StartTimerDecisionAttributes();
    timer.setStartToFireTimeoutSeconds(delaySeconds);
    final String timerId = decisions.getNextId();
    timer.setTimerId(timerId);
    decisions.startTimer(timer, null);
    context.setCompletionHandle((ctx, e) -> callback.accept(e));
    scheduledTimers.put(timerId, context);
    return new ClockDecisionContext.TimerCancellationHandler(timerId);
  }

  void setReplaying(boolean replaying) {
    this.replaying = replaying;
  }

  void handleTimerFired(TimerFiredEventAttributes attributes) {
    String timerId = attributes.getTimerId();
    if (decisions.handleTimerClosed(timerId)) {
      OpenRequestInfo<?, Long> scheduled = scheduledTimers.remove(timerId);
      if (scheduled != null) {
        BiConsumer<?, Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, null);
      }
    }
  }

  void handleTimerCanceled(HistoryEvent event) {
    TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
    String timerId = attributes.getTimerId();
    if (decisions.handleTimerCanceled(event)) {
      timerCancelled(timerId, null);
    }
  }

  private void timerCancelled(String timerId, Exception reason) {
    OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(timerId);
    if (scheduled == null) {
      return;
    }
    BiConsumer<?, Exception> context = scheduled.getCompletionCallback();
    CancellationException exception = new CancellationException("Cancelled by request");
    exception.initCause(reason);
    context.accept(null, exception);
  }
}
