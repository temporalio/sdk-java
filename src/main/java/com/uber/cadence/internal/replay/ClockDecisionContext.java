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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Clock that must be used inside workflow definition code to ensure replay
 * determinism.
 * TODO: Refactor to become a helper for managing timers instead of the generic clock class.
 */
final class ClockDecisionContext {

    private final class TimerCancellationHandler implements Consumer<Throwable> {

        private final String timerId;

        TimerCancellationHandler(String timerId) {
            this.timerId = timerId;
        }

        @Override
        public void accept(Throwable reason) {
            decisions.cancelTimer(timerId, () -> {
                OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(timerId);
                BiConsumer<?, RuntimeException> context = scheduled.getCompletionCallback();
                CancellationException exception = new CancellationException("Cancelled by request");
                exception.initCause(reason);
                context.accept(null, exception);
            });
        }
    }

    private final DecisionsHelper decisions;

    private final Map<String, OpenRequestInfo<?, Long>> scheduledTimers = new HashMap<>();

    private final SortedMap<Long, String> timersByFiringTime = new TreeMap<>();

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

    Consumer<Throwable> createTimer(long delaySeconds, Consumer<Throwable> callback) {
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("Negative delaySeconds: " + delaySeconds);
        }
        if (delaySeconds == 0) {
            callback.accept(null);
            return  throwable -> {};
        }
        long firingTime = currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
        // As the timer resolution is 1 second it doesn't really make sense to update a timer
        // that is less than one second before the already existing.
        if (timersByFiringTime.size() > 0) {
            long nextTimerFiringTime = timersByFiringTime.firstKey();
            if (firingTime > nextTimerFiringTime
                    || nextTimerFiringTime - firingTime < TimeUnit.SECONDS.toMillis(1)) {
                return null;
            }
        }
        Consumer<Throwable> result = null;
        if (!timersByFiringTime.containsKey(firingTime)) {
            final OpenRequestInfo<?, Long> context = new OpenRequestInfo<>(firingTime);
            final StartTimerDecisionAttributes timer = new StartTimerDecisionAttributes();
            timer.setStartToFireTimeoutSeconds(delaySeconds);
            final String timerId = decisions.getNextId();
            timer.setTimerId(timerId);
            decisions.startTimer(timer, null);
            context.setCompletionHandle((ctx, throwable) -> callback.accept(null));
            scheduledTimers.put(timerId, context);
            timersByFiringTime.put(firingTime, timerId);
            result = new ClockDecisionContext.TimerCancellationHandler(timerId);
        }
        SortedMap<Long, String> toCancel = timersByFiringTime.subMap(0l, firingTime);
        for (String timerId : toCancel.values()) {
            decisions.cancelTimer(timerId, () -> {
                OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(timerId);
                BiConsumer<?, RuntimeException> context = scheduled.getCompletionCallback();
                CancellationException exception = new CancellationException("Cancelled as next unblock time changed");
                context.accept(null, exception);
            });
        }
        toCancel.clear();
        return result;
    }

    void cancelAllTimers() {
        for (String timerId : timersByFiringTime.values()) {
            decisions.cancelTimer(timerId, () -> {
                OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(timerId);
                BiConsumer<?, RuntimeException> context = scheduled.getCompletionCallback();
                CancellationException exception = new CancellationException("Cancelled as next unblock time changed");
                context.accept(null, exception);
            });
        }
        timersByFiringTime.clear();
    }

    void setReplaying(boolean replaying) {
        this.replaying = replaying;
    }

    void handleTimerFired(TimerFiredEventAttributes attributes) {
        String timerId = attributes.getTimerId();
        if (decisions.handleTimerClosed(timerId)) {
            OpenRequestInfo<?, Long> scheduled = scheduledTimers.remove(timerId);
            if (scheduled != null) {
                BiConsumer<?, RuntimeException>  completionCallback = scheduled.getCompletionCallback();
                completionCallback.accept(null, null);
                long firingTime = scheduled.getUserContext();
                timersByFiringTime.remove(firingTime);
            }
        }
    }

    void handleTimerCanceled(HistoryEvent event) {
        TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
        String timerId = attributes.getTimerId();
        if (decisions.handleTimerCanceled(event)) {
            OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(timerId);
            if (scheduled != null) {
                BiConsumer<?, RuntimeException> completionCallback = scheduled.getCompletionCallback();
                CancellationException exception = new CancellationException("Cancelled by request");
                completionCallback.accept(null, exception);
            }
        }
    }
}
