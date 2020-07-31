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

import static io.temporal.internal.replay.MarkerHandler.MUTABLE_MARKER_DATA_KEY;

import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.TimerCanceledEventAttributes;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.LocalActivityMarkerData;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Clock that must be used inside workflow definition code to ensure replay determinism. */
public final class ReplayClockContext {

  private static final String SIDE_EFFECT_MARKER_NAME = "SideEffect";
  private static final String MUTABLE_SIDE_EFFECT_MARKER_NAME = "MutableSideEffect";
  public static final String VERSION_MARKER_NAME = "Version";
  public static final String LOCAL_ACTIVITY_MARKER_NAME = "LocalActivity";

  private static final Logger log = LoggerFactory.getLogger(ReplayClockContext.class);

  private final class TimerCancellationHandler implements Consumer<Exception> {

    private final long startEventId;

    TimerCancellationHandler(long timerId) {
      this.startEventId = timerId;
    }

    @Override
    public void accept(Exception reason) {
      commandHelper.cancelTimer(startEventId, () -> timerCancelled(startEventId, reason));
    }
  }

  private final CommandHelper commandHelper;
  // key is startedEventId
  private final Map<Long, OpenRequestInfo<?, Long>> scheduledTimers = new HashMap<>();
  private long replayCurrentTimeMilliseconds = -1;
  // Local time when replayCurrentTimeMilliseconds was updated.
  private long replayTimeUpdatedAtMillis = -1;
  private boolean replaying = true;
  // Key is side effect marker eventId
  private final Map<Long, Optional<Payloads>> sideEffectResults = new HashMap<>();
  private final MarkerHandler mutableSideEffectHandler;
  private final MarkerHandler versionHandler;
  private final BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller;
  private final Map<String, OpenRequestInfo<Optional<Payloads>, ActivityType>> pendingLaTasks =
      new HashMap<>();
  private final Map<String, ExecuteLocalActivityParameters> unstartedLaTasks = new HashMap<>();
  private final ReplayWorkflowExecutor workflowExecutor;
  private final DataConverter dataConverter;
  private final Condition taskCondition;
  private boolean taskCompleted = false;

  ReplayClockContext(
      CommandHelper commandHelper,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller,
      ReplayWorkflowExecutor workflowExecutor,
      DataConverter dataConverter) {
    this.commandHelper = commandHelper;
    this.taskCondition = workflowExecutor.getLock().newCondition();
    mutableSideEffectHandler =
        new MarkerHandler(commandHelper, MUTABLE_SIDE_EFFECT_MARKER_NAME, () -> replaying);
    versionHandler = new MarkerHandler(commandHelper, VERSION_MARKER_NAME, () -> replaying);
    this.laTaskPoller = laTaskPoller;
    this.workflowExecutor = workflowExecutor;
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

  Consumer<Exception> createTimer(Duration delay, Consumer<Exception> callback) {
    if (delay.isNegative()) {
      throw new IllegalArgumentException("Negative delay: " + delay);
    }
    if (delay.isZero()) {
      callback.accept(null);
      return null;
    }
    long firingTime = currentTimeMillis() + delay.toMillis();
    final OpenRequestInfo<?, Long> context = new OpenRequestInfo<>(firingTime);
    final StartTimerCommandAttributes timer =
        StartTimerCommandAttributes.newBuilder()
            .setStartToFireTimeout(ProtobufTimeUtils.ToProtoDuration(delay))
            .setTimerId(String.valueOf(commandHelper.getAndIncrementNextId()))
            .build();
    long startEventId = commandHelper.startTimer(timer);
    context.setCompletionHandle((ctx, e) -> callback.accept(e));
    scheduledTimers.put(startEventId, context);
    return new TimerCancellationHandler(startEventId);
  }

  void setReplaying(boolean replaying) {
    this.replaying = replaying;
  }

  void handleTimerFired(TimerFiredEventAttributes attributes) {
    long startedEventId = attributes.getStartedEventId();
    if (commandHelper.handleTimerClosed(attributes)) {
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
    if (commandHelper.handleTimerCanceled(event)) {
      timerCancelled(startedEventId, null);
    }
  }

  private void timerCancelled(long startEventId, Exception reason) {
    OpenRequestInfo<?, ?> scheduled = scheduledTimers.remove(startEventId);
    if (scheduled == null) {
      return;
    }
    BiConsumer<?, Exception> context = scheduled.getCompletionCallback();
    CanceledFailure exception = new CanceledFailure("Cancelled by request");
    if (reason != null) {
      exception.initCause(reason);
    }
    context.accept(null, exception);
  }

  Optional<Payloads> sideEffect(Func<Optional<Payloads>> func) {
    commandHelper.addAllMissingVersionMarker();
    long sideEffectEventId = commandHelper.getNextCommandEventId();
    Optional<Payloads> result;
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
    Map<String, Payloads> details = new HashMap<>();
    if (result.isPresent()) {
      details.put(MUTABLE_MARKER_DATA_KEY, result.get());
    }
    commandHelper.recordMarker(
        SIDE_EFFECT_MARKER_NAME, Optional.empty(), details, Optional.empty());
    return result;
  }

  /**
   * @param id mutable side effect id
   * @param func given the value from the last marker returns value to store. If result is empty
   *     nothing is recorded into the history.
   * @return the latest value returned by func
   */
  Optional<Payloads> mutableSideEffect(
      String id, DataConverter converter, Func1<Optional<Payloads>, Optional<Payloads>> func) {
    commandHelper.addAllMissingVersionMarker();
    return mutableSideEffectHandler.handle(id, converter, func);
  }

  void upsertSearchAttributes(SearchAttributes searchAttributes) {
    commandHelper.upsertSearchAttributes(searchAttributes);
  }

  void handleMarkerRecorded(HistoryEvent event) {
    MarkerRecordedEventAttributes attributes = event.getMarkerRecordedEventAttributes();
    String name = attributes.getMarkerName();
    if (SIDE_EFFECT_MARKER_NAME.equals(name)) {
      Optional<Payloads> details =
          attributes.containsDetails(MUTABLE_MARKER_DATA_KEY)
              ? Optional.of(attributes.getDetailsOrThrow(MUTABLE_MARKER_DATA_KEY))
              : Optional.empty();
      sideEffectResults.put(event.getEventId(), details);
    } else if (LOCAL_ACTIVITY_MARKER_NAME.equals(name)) {
      handleLocalActivityMarker(event.getEventId(), attributes);
    } else if (!MUTABLE_SIDE_EFFECT_MARKER_NAME.equals(name) && !VERSION_MARKER_NAME.equals(name)) {
      if (log.isWarnEnabled()) {
        log.warn("Unexpected marker: " + event);
      }
    }
  }

  private void handleLocalActivityMarker(long eventId, MarkerRecordedEventAttributes attributes) {
    LocalActivityMarkerData marker =
        LocalActivityMarkerData.fromEventAttributes(attributes, dataConverter);
    if (pendingLaTasks.containsKey(marker.getActivityId())) {
      if (log.isDebugEnabled()) {
        log.debug("Handle LocalActivityMarker for activity " + marker.getActivityId());
      }
      Map<String, Payloads> details = attributes.getDetailsMap();
      Optional<Header> header =
          attributes.hasHeader() ? Optional.of(attributes.getHeader()) : Optional.empty();
      commandHelper.recordMarker(LOCAL_ACTIVITY_MARKER_NAME, header, details, marker.getFailure());

      OpenRequestInfo<Optional<Payloads>, ActivityType> scheduled =
          pendingLaTasks.remove(marker.getActivityId());
      unstartedLaTasks.remove(marker.getActivityId());

      Exception failure = null;
      if (marker.getFailure().isPresent()) {
        failure =
            new ActivityTaskFailedException(
                eventId,
                0,
                0,
                ActivityType.newBuilder().setName(marker.getActivityType()).build(),
                marker.getActivityId(),
                marker.getFailure().get());
      }

      BiConsumer<Optional<Payloads>, Exception> completionHandle =
          scheduled.getCompletionCallback();
      completionHandle.accept(marker.getResult(), failure);
      setReplayCurrentTimeMilliseconds(marker.getReplayTimeMillis());

      taskCompleted = true;
      // This method is already called under the lock.
      taskCondition.signal();
    } else {
      log.warn(
          "Local Activity completion ignored for eventId="
              + eventId
              + ", activityId="
              + marker.getActivityId()
              + ", activityType="
              + marker.getActivityType());
    }
  }

  /**
   * During replay getVersion should account for the following situations at the current eventId.
   *
   * <ul>
   *   <li>There is correspondent Marker with the same changeId: return version from the marker.
   *   <li>There is no Marker with the same changeId: return DEFAULT_VERSION,
   *   <li>There is marker with a different changeId (possibly more than one) and the marker with
   *       matching changeId follows them: add fake commands for all the version markers that
   *       precede the matching one as the correspondent getVersion calls were removed
   *   <li>There is marker with a different changeId (possibly more than one) and no marker with
   *       matching changeId follows them: return DEFAULT_VERSION as it looks like the getVersion
   *       was added after that part of code has executed
   *   <li>Another case is when there is no call to getVersion and there is a version marker: insert
   *       fake commands for all version markers up to the event that caused the lookup.
   * </ul>
   */
  int getVersion(String changeId, DataConverter converter, int minSupported, int maxSupported) {
    commandHelper.addAllMissingVersionMarker(Optional.of(changeId), Optional.of(converter));

    Optional<Payloads> result =
        versionHandler.handle(
            changeId,
            converter,
            (stored) -> {
              if (stored.isPresent()) {
                return Optional.empty();
              }
              return converter.toPayloads(maxSupported);
            });

    if (!result.isPresent()) {
      return WorkflowInternal.DEFAULT_VERSION;
    }
    int version = converter.fromPayloads(result, Integer.class, Integer.class);
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
      ExecuteLocalActivityParameters params, BiConsumer<Optional<Payloads>, Exception> callback) {
    PollActivityTaskQueueResponse.Builder activityTask = params.getActivityTask();
    final OpenRequestInfo<Optional<Payloads>, ActivityType> context =
        new OpenRequestInfo<>(activityTask.getActivityType());
    context.setCompletionHandle(callback);
    String activityId = activityTask.getActivityId();
    if (activityId.isEmpty()) {
      activityId = commandHelper.getAndIncrementNextId();
      activityTask.setActivityId(activityId);
    }
    pendingLaTasks.put(activityId, context);
    unstartedLaTasks.put(activityId, params);
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
                  workflowExecutor.getLocalActivityCompletionSink(),
                  workflowExecutor.getWorkflowTaskTimeout(),
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
