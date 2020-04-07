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

package io.temporal.internal.replay;

import com.google.common.base.Strings;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.LocalActivityMarkerData;
import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.decision.StartTimerDecisionAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.MarkerRecordedEventAttributes;
import io.temporal.proto.event.TimerCanceledEventAttributes;
import io.temporal.proto.event.TimerFiredEventAttributes;
import io.temporal.workflow.ActivityFailureException;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Clock that must be used inside workflow definition code to ensure replay determinism. */
public final class ClockDecisionContext {

  private static final String SIDE_EFFECT_MARKER_NAME = "SideEffect";
  private static final String MUTABLE_SIDE_EFFECT_MARKER_NAME = "MutableSideEffect";
  public static final String VERSION_MARKER_NAME = "Version";
  public static final String LOCAL_ACTIVITY_MARKER_NAME = "LocalActivity";

  private static final Logger log = LoggerFactory.getLogger(ClockDecisionContext.class);

  private final class TimerCancellationHandler implements Consumer<Exception> {

    private final long startEventId;

    TimerCancellationHandler(long timerId) {
      this.startEventId = timerId;
    }

    @Override
    public void accept(Exception reason) {
      decisions.cancelTimer(startEventId, () -> timerCancelled(startEventId, reason));
    }
  }

  private final DecisionsHelper decisions;
  // key is startedEventId
  private final Map<Long, OpenRequestInfo<?, Long>> scheduledTimers = new HashMap<>();
  private long replayCurrentTimeMilliseconds = -1;
  // Local time when replayCurrentTimeMilliseconds was updated.
  private long replayTimeUpdatedAtMillis = -1;
  private boolean replaying = true;
  // Key is side effect marker eventId
  private final Map<Long, byte[]> sideEffectResults = new HashMap<>();
  private final MarkerHandler mutableSideEffectHandler;
  private final MarkerHandler versionHandler;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller;
  private final Map<String, OpenRequestInfo<byte[], ActivityType>> pendingLaTasks = new HashMap<>();
  private final Map<String, ExecuteLocalActivityParameters> unstartedLaTasks = new HashMap<>();
  private final ReplayDecider replayDecider;
  private final DataConverter dataConverter;
  private final Condition taskCondition;
  private boolean taskCompleted = false;

  ClockDecisionContext(
      DecisionsHelper decisions,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller,
      ReplayDecider replayDecider,
      DataConverter dataConverter) {
    this.decisions = decisions;
    this.taskCondition = replayDecider.getLock().newCondition();
    mutableSideEffectHandler =
        new MarkerHandler(decisions, MUTABLE_SIDE_EFFECT_MARKER_NAME, () -> replaying);
    versionHandler = new MarkerHandler(decisions, VERSION_MARKER_NAME, () -> replaying);
    this.laTaskPoller = laTaskPoller;
    this.replayDecider = replayDecider;
    this.dataConverter = dataConverter;
  }

  public long currentTimeMillis() {
    return replayCurrentTimeMilliseconds;
  }

  private long replayTimeUpdatedAtMillis() {
    return replayTimeUpdatedAtMillis;
  }

  void setReplayCurrentTimeMilliseconds(long replayCurrentTimeMilliseconds) {
    if (this.replayCurrentTimeMilliseconds < replayCurrentTimeMilliseconds) {
      this.replayCurrentTimeMilliseconds = replayCurrentTimeMilliseconds;
      this.replayTimeUpdatedAtMillis = System.currentTimeMillis();
    }
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
    final StartTimerDecisionAttributes timer =
        StartTimerDecisionAttributes.newBuilder()
            .setStartToFireTimeoutSeconds(delaySeconds)
            .setTimerId(String.valueOf(decisions.getAndIncrementNextId()))
            .build();
    long startEventId = decisions.startTimer(timer);
    context.setCompletionHandle((ctx, e) -> callback.accept(e));
    scheduledTimers.put(startEventId, context);
    return new TimerCancellationHandler(startEventId);
  }

  void setReplaying(boolean replaying) {
    this.replaying = replaying;
  }

  void handleTimerFired(TimerFiredEventAttributes attributes) {
    long startedEventId = attributes.getStartedEventId();
    if (decisions.handleTimerClosed(attributes)) {
      OpenRequestInfo<?, Long> scheduled = scheduledTimers.remove(startedEventId);
      if (scheduled != null) {
        // Server doesn't guarantee that the timer fire timestamp is larger or equal of the
        // expected fire time. So fix the time or timer firing will be ignored.
        long firingTime = scheduled.getUserContext();
        if (replayCurrentTimeMilliseconds < firingTime) {
          setReplayCurrentTimeMilliseconds(firingTime);
        }
        BiConsumer<?, Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, null);
      }
    }
  }

  void handleTimerCanceled(HistoryEvent event) {
    TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
    long startedEventId = attributes.getStartedEventId();
    if (decisions.handleTimerCanceled(event)) {
      timerCancelled(startedEventId, null);
    }
  }

  private void timerCancelled(long startEventId, Exception reason) {
    OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(startEventId);
    if (scheduled == null) {
      return;
    }
    BiConsumer<?, Exception> context = scheduled.getCompletionCallback();
    CancellationException exception = new CancellationException("Cancelled by request");
    exception.initCause(reason);
    context.accept(null, exception);
  }

  byte[] sideEffect(Func<byte[]> func) {
    decisions.addAllMissingVersionMarker(false, Optional.empty());
    long sideEffectEventId = decisions.getNextDecisionEventId();
    byte[] result;
    if (replaying) {
      result = sideEffectResults.get(sideEffectEventId);
      if (result == null) {
        throw new Error("No cached result found for SideEffect EventId=" + sideEffectEventId);
      }
    } else {
      try {
        result = func.apply();
      } catch (Error e) {
        throw e;
      } catch (Exception e) {
        throw new Error("sideEffect function failed", e);
      }
    }
    decisions.recordMarker(SIDE_EFFECT_MARKER_NAME, null, result);
    return result;
  }

  /**
   * @param id mutable side effect id
   * @param func given the value from the last marker returns value to store. If result is empty
   *     nothing is recorded into the history.
   * @return the latest value returned by func
   */
  Optional<byte[]> mutableSideEffect(
      String id, DataConverter converter, Func1<Optional<byte[]>, Optional<byte[]>> func) {
    decisions.addAllMissingVersionMarker(false, Optional.empty());
    return mutableSideEffectHandler.handle(id, converter, func);
  }

  void upsertSearchAttributes(SearchAttributes searchAttributes) {
    decisions.upsertSearchAttributes(searchAttributes);
  }

  void handleMarkerRecorded(HistoryEvent event) {
    MarkerRecordedEventAttributes attributes = event.getMarkerRecordedEventAttributes();
    String name = attributes.getMarkerName();
    if (SIDE_EFFECT_MARKER_NAME.equals(name)) {
      sideEffectResults.put(event.getEventId(), attributes.getDetails().toByteArray());
    } else if (LOCAL_ACTIVITY_MARKER_NAME.equals(name)) {
      handleLocalActivityMarker(attributes);
    } else if (!MUTABLE_SIDE_EFFECT_MARKER_NAME.equals(name) && !VERSION_MARKER_NAME.equals(name)) {
      if (log.isWarnEnabled()) {
        log.warn("Unexpected marker: " + event);
      }
    }
  }

  private void handleLocalActivityMarker(MarkerRecordedEventAttributes attributes) {
    LocalActivityMarkerData marker =
        LocalActivityMarkerData.fromEventAttributes(attributes, dataConverter);
    if (pendingLaTasks.containsKey(marker.getActivityId())) {
      log.debug("Handle LocalActivityMarker for activity " + marker.getActivityId());

      decisions.recordMarker(
          LOCAL_ACTIVITY_MARKER_NAME,
          marker.getHeader(dataConverter),
          attributes.getDetails().toByteArray());

      OpenRequestInfo<byte[], ActivityType> scheduled =
          pendingLaTasks.remove(marker.getActivityId());
      unstartedLaTasks.remove(marker.getActivityId());

      Exception failure = null;
      if (marker.getIsCancelled()) {
        failure = new CancellationException(marker.getErrReason());
      } else if (marker.getErrJson() != null) {
        Throwable cause =
            dataConverter.fromData(marker.getErrJson(), Throwable.class, Throwable.class);
        ActivityType activityType =
            ActivityType.newBuilder().setName(marker.getActivityType()).build();
        failure =
            new ActivityFailureException(
                attributes.getDecisionTaskCompletedEventId(),
                activityType,
                marker.getActivityId(),
                cause,
                marker.getAttempt(),
                marker.getBackoff());
      }

      BiConsumer<byte[], Exception> completionHandle = scheduled.getCompletionCallback();
      completionHandle.accept(marker.getResult(), failure);
      setReplayCurrentTimeMilliseconds(marker.getReplayTimeMillis());

      taskCompleted = true;
      // This method is already called under the lock.
      taskCondition.signal();
    }
  }

  int getVersion(String changeId, DataConverter converter, int minSupported, int maxSupported) {
    Predicate<MarkerRecordedEventAttributes> changeIdEquals =
        (attributes) -> {
          MarkerHandler.MarkerInterface markerData =
              MarkerHandler.MarkerInterface.fromEventAttributes(attributes, converter);
          return markerData.getId().equals(changeId);
        };
    decisions.addAllMissingVersionMarker(true, Optional.of(changeIdEquals));

    Optional<byte[]> result =
        versionHandler.handle(
            changeId,
            converter,
            (stored) -> {
              if (stored.isPresent()) {
                return Optional.empty();
              }
              return Optional.of(converter.toData(maxSupported));
            });

    if (!result.isPresent()) {
      return WorkflowInternal.DEFAULT_VERSION;
    }
    int version = converter.fromData(result.get(), Integer.class, Integer.class);
    validateVersion(changeId, version, minSupported, maxSupported);
    return version;
  }

  private void validateVersion(String changeId, int version, int minSupported, int maxSupported) {
    if ((version < minSupported || version > maxSupported)
        && version != WorkflowInternal.DEFAULT_VERSION) {
      throw new Error(
          String.format(
              "Version %d of changeId %s is not supported. Supported version is between %d and %d.",
              version, changeId, minSupported, maxSupported));
    }
  }

  Consumer<Exception> scheduleLocalActivityTask(
      ExecuteLocalActivityParameters params, BiConsumer<byte[], Exception> callback) {
    final OpenRequestInfo<byte[], ActivityType> context =
        new OpenRequestInfo<>(params.getActivityType());
    context.setCompletionHandle(callback);
    if (Strings.isNullOrEmpty(params.getActivityId())) {
      params.setActivityId(decisions.getAndIncrementNextId());
    }
    pendingLaTasks.put(params.getActivityId(), context);
    unstartedLaTasks.put(params.getActivityId(), params);
    return null;
  }

  boolean startUnstartedLaTasks(Duration maxWaitAllowed) {
    long startTime = System.currentTimeMillis();
    for (ExecuteLocalActivityParameters params : unstartedLaTasks.values()) {
      long currTime = System.currentTimeMillis();
      maxWaitAllowed = maxWaitAllowed.minus(Duration.ofMillis(currTime - startTime));
      boolean applied =
          laTaskPoller.apply(
              new LocalActivityWorker.Task(
                  params,
                  replayDecider.getLocalActivityCompletionSink(),
                  replayDecider.getDecisionTimeoutSeconds(),
                  this::currentTimeMillis,
                  this::replayTimeUpdatedAtMillis),
              maxWaitAllowed);
      if (!applied) {
        return false;
      }
    }
    unstartedLaTasks.clear();
    return true;
  }

  int numPendingLaTasks() {
    return pendingLaTasks.size();
  }

  void awaitTaskCompletion(Duration duration) throws InterruptedException {
    while (!taskCompleted) {
      // This call is called from already locked object
      taskCondition.awaitNanos(duration.toNanos());
    }
    taskCompleted = false;
  }
}
