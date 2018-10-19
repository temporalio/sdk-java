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

package com.uber.cadence.internal.testservice;

import com.google.common.base.Throwables;
import com.uber.cadence.BadRequestError;
import com.uber.cadence.CancelTimerDecisionAttributes;
import com.uber.cadence.CancelTimerFailedEventAttributes;
import com.uber.cadence.CancelWorkflowExecutionDecisionAttributes;
import com.uber.cadence.ChildWorkflowExecutionCanceledEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionStartedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionTimedOutEventAttributes;
import com.uber.cadence.CompleteWorkflowExecutionDecisionAttributes;
import com.uber.cadence.ContinueAsNewWorkflowExecutionDecisionAttributes;
import com.uber.cadence.Decision;
import com.uber.cadence.DecisionTaskFailedCause;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.EventType;
import com.uber.cadence.FailWorkflowExecutionDecisionAttributes;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.MarkerRecordedEventAttributes;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryFailedError;
import com.uber.cadence.QueryTaskCompletedType;
import com.uber.cadence.QueryWorkflowRequest;
import com.uber.cadence.QueryWorkflowResponse;
import com.uber.cadence.RecordActivityTaskHeartbeatRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatResponse;
import com.uber.cadence.RecordMarkerDecisionAttributes;
import com.uber.cadence.RequestCancelActivityTaskDecisionAttributes;
import com.uber.cadence.RequestCancelActivityTaskFailedEventAttributes;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.RespondActivityTaskCanceledByIDRequest;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedByIDRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedByIDRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.ScheduleActivityTaskDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartChildWorkflowExecutionDecisionAttributes;
import com.uber.cadence.StartChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.StartTimerDecisionAttributes;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StickyExecutionAttributes;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecutionCloseStatus;
import com.uber.cadence.WorkflowExecutionSignaledEventAttributes;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.testservice.StateMachines.Action;
import com.uber.cadence.internal.testservice.StateMachines.ActivityTaskData;
import com.uber.cadence.internal.testservice.StateMachines.ChildWorkflowData;
import com.uber.cadence.internal.testservice.StateMachines.DecisionTaskData;
import com.uber.cadence.internal.testservice.StateMachines.SignalExternalData;
import com.uber.cadence.internal.testservice.StateMachines.State;
import com.uber.cadence.internal.testservice.StateMachines.TimerData;
import com.uber.cadence.internal.testservice.StateMachines.WorkflowData;
import com.uber.cadence.internal.testservice.TestWorkflowStore.TaskListId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestWorkflowMutableStateImpl implements TestWorkflowMutableState {

  @FunctionalInterface
  private interface UpdateProcedure {

    void apply(RequestContext ctx)
        throws InternalServiceError, BadRequestError, EntityNotExistsError;
  }

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowMutableStateImpl.class);

  private static final int MILLISECONDS_IN_SECOND = 1000;
  private final Lock lock = new ReentrantLock();
  private final SelfAdvancingTimer selfAdvancingTimer;
  private final LongSupplier clock;
  private final ExecutionId executionId;
  private final Optional<TestWorkflowMutableState> parent;
  private final OptionalLong parentChildInitiatedEventId;
  private final TestWorkflowStore store;
  private final TestWorkflowService service;
  private final StartWorkflowExecutionRequest startRequest;
  private long nextEventId;
  private final List<RequestContext> concurrentToDecision = new ArrayList<>();
  private final Map<String, StateMachine<ActivityTaskData>> activities = new HashMap<>();
  private final Map<Long, StateMachine<ChildWorkflowData>> childWorkflows = new HashMap<>();
  private final Map<String, StateMachine<TimerData>> timers = new HashMap<>();
  private final Map<String, StateMachine<SignalExternalData>> externalSignals = new HashMap<>();
  private StateMachine<WorkflowData> workflow;
  private volatile StateMachine<DecisionTaskData> decision;
  private long lastNonFailedDecisionStartEventId;
  private final Map<String, CompletableFuture<QueryWorkflowResponse>> queries =
      new ConcurrentHashMap<>();
  private final Map<String, PollForDecisionTaskResponse> queryRequests = new ConcurrentHashMap<>();
  public StickyExecutionAttributes stickyExecutionAttributes;

  /** @param parentChildInitiatedEventId id of the child initiated event in the parent history */
  TestWorkflowMutableStateImpl(
      StartWorkflowExecutionRequest startRequest,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      TestWorkflowService service,
      TestWorkflowStore store) {
    this.startRequest = startRequest;
    this.parent = parent;
    this.parentChildInitiatedEventId = parentChildInitiatedEventId;
    this.service = service;
    String runId = UUID.randomUUID().toString();
    this.executionId =
        new ExecutionId(startRequest.getDomain(), startRequest.getWorkflowId(), runId);
    this.store = store;
    selfAdvancingTimer = store.getTimer();
    this.clock = selfAdvancingTimer.getClock();
    this.workflow = StateMachines.newWorkflowStateMachine();
  }

  private void update(UpdateProcedure updater)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    update(false, updater, stackTraceElements[2].getMethodName());
  }

  private void completeDecisionUpdate(UpdateProcedure updater, StickyExecutionAttributes attributes)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    stickyExecutionAttributes = attributes;
    update(true, updater, stackTraceElements[2].getMethodName());
  }

  private void update(boolean completeDecisionUpdate, UpdateProcedure updater, String caller)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    String callerInfo = "Decision Update from " + caller;
    lock.lock();
    LockHandle lockHandle = selfAdvancingTimer.lockTimeSkipping(callerInfo);

    try {
      checkCompleted();
      boolean concurrentDecision =
          !completeDecisionUpdate
              && (decision != null && decision.getState() == StateMachines.State.STARTED);

      RequestContext ctx = new RequestContext(clock, this, nextEventId);
      updater.apply(ctx);
      if (concurrentDecision && workflow.getState() != State.TIMED_OUT) {
        concurrentToDecision.add(ctx);
        ctx.fireCallbacks(0);
        store.applyTimersAndLocks(ctx);
      } else {
        nextEventId = ctx.commitChanges(store);
      }
    } catch (InternalServiceError | EntityNotExistsError | BadRequestError e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    } finally {
      lockHandle.unlock();
      lock.unlock();
    }
  }

  @Override
  public ExecutionId getExecutionId() {
    return executionId;
  }

  @Override
  public Optional<WorkflowExecutionCloseStatus> getCloseStatus() {
    switch (workflow.getState()) {
      case NONE:
      case INITIATED:
      case STARTED:
      case CANCELLATION_REQUESTED:
        return Optional.empty();
      case FAILED:
        return Optional.of(WorkflowExecutionCloseStatus.FAILED);
      case TIMED_OUT:
        return Optional.of(WorkflowExecutionCloseStatus.TIMED_OUT);
      case CANCELED:
        return Optional.of(WorkflowExecutionCloseStatus.CANCELED);
      case COMPLETED:
        return Optional.of(WorkflowExecutionCloseStatus.COMPLETED);
      case CONTINUED_AS_NEW:
        return Optional.of(WorkflowExecutionCloseStatus.CONTINUED_AS_NEW);
    }
    throw new IllegalStateException("unreachable");
  }

  @Override
  public StartWorkflowExecutionRequest getStartRequest() {
    return startRequest;
  }

  @Override
  public StickyExecutionAttributes getStickyExecutionAttributes() {
    return stickyExecutionAttributes;
  }

  @Override
  public void startDecisionTask(
      PollForDecisionTaskResponse task, PollForDecisionTaskRequest pollRequest)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    if (task.getQuery() == null) {
      update(
          ctx -> {
            long scheduledEventId = decision.getData().scheduledEventId;
            decision.action(StateMachines.Action.START, ctx, pollRequest, 0);
            ctx.addTimer(
                stickyExecutionAttributes != null
                    ? stickyExecutionAttributes.getScheduleToStartTimeoutSeconds()
                    : startRequest.getTaskStartToCloseTimeoutSeconds(),
                () -> timeoutDecisionTask(scheduledEventId),
                "DecisionTask StartToCloseTimeout");
          });
    }
  }

  @Override
  public void completeDecisionTask(int historySize, RespondDecisionTaskCompletedRequest request)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    List<Decision> decisions = request.getDecisions();
    completeDecisionUpdate(
        ctx -> {
          if (ctx.getInitialEventId() != historySize + 1) {
            throw new BadRequestError(
                "Expired decision: expectedHistorySize="
                    + historySize
                    + ","
                    + " actualHistorySize="
                    + ctx.getInitialEventId());
          }
          long decisionTaskCompletedId = ctx.getNextEventId() - 1;
          // Fail the decision if there are new events and the decision tries to complete the
          // workflow
          if (!concurrentToDecision.isEmpty() && hasCompleteDecision(request.getDecisions())) {
            RespondDecisionTaskFailedRequest failedRequest =
                new RespondDecisionTaskFailedRequest()
                    .setCause(DecisionTaskFailedCause.UNHANDLED_DECISION)
                    .setIdentity(request.getIdentity());
            decision.action(Action.FAIL, ctx, failedRequest, decisionTaskCompletedId);
            for (RequestContext deferredCtx : this.concurrentToDecision) {
              ctx.add(deferredCtx);
            }
            this.concurrentToDecision.clear();

            // Reset sticky execution attributes on failure
            stickyExecutionAttributes = null;
            scheduleDecision(ctx);
            return;
          }
          if (decision == null) {
            throw new EntityNotExistsError("No outstanding decision");
          }
          decision.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          for (Decision d : decisions) {
            processDecision(ctx, d, request.getIdentity(), decisionTaskCompletedId);
          }
          for (RequestContext deferredCtx : this.concurrentToDecision) {
            ctx.add(deferredCtx);
          }
          lastNonFailedDecisionStartEventId = this.decision.getData().startedEventId;
          this.decision = null;
          boolean completed =
              workflow.getState() == StateMachines.State.COMPLETED
                  || workflow.getState() == StateMachines.State.FAILED
                  || workflow.getState() == StateMachines.State.CANCELED;
          if (!completed && (ctx.isNeedDecision() || !this.concurrentToDecision.isEmpty())) {
            scheduleDecision(ctx);
          }
          this.concurrentToDecision.clear();
          ctx.unlockTimer();
        },
        request.getStickyAttributes());
  }

  private boolean hasCompleteDecision(List<Decision> decisions) {
    for (Decision d : decisions) {
      if (WorkflowExecutionUtils.isWorkflowExecutionCompleteDecision(d)) {
        return true;
      }
    }
    return false;
  }

  private void processDecision(
      RequestContext ctx, Decision d, String identity, long decisionTaskCompletedId)
      throws BadRequestError, InternalServiceError, EntityNotExistsError {
    switch (d.getDecisionType()) {
      case CompleteWorkflowExecution:
        processCompleteWorkflowExecution(
            ctx, d.getCompleteWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case FailWorkflowExecution:
        processFailWorkflowExecution(
            ctx, d.getFailWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case CancelWorkflowExecution:
        processCancelWorkflowExecution(
            ctx, d.getCancelWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case ContinueAsNewWorkflowExecution:
        processContinueAsNewWorkflowExecution(
            ctx,
            d.getContinueAsNewWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId,
            identity);
        break;
      case ScheduleActivityTask:
        processScheduleActivityTask(
            ctx, d.getScheduleActivityTaskDecisionAttributes(), decisionTaskCompletedId);
        break;
      case RequestCancelActivityTask:
        processRequestCancelActivityTask(
            ctx, d.getRequestCancelActivityTaskDecisionAttributes(), decisionTaskCompletedId);
        break;
      case StartTimer:
        processStartTimer(ctx, d.getStartTimerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case CancelTimer:
        processCancelTimer(ctx, d.getCancelTimerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case StartChildWorkflowExecution:
        processStartChildWorkflow(
            ctx, d.getStartChildWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case SignalExternalWorkflowExecution:
        processSignalExternalWorkflowExecution(
            ctx, d.getSignalExternalWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case RecordMarker:
        processRecordMarker(ctx, d.getRecordMarkerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case RequestCancelExternalWorkflowExecution:
        throw new InternalServiceError(
            "Decision " + d.getDecisionType() + " is not yet " + "implemented");
    }
  }

  private void processRecordMarker(
      RequestContext ctx, RecordMarkerDecisionAttributes attr, long decisionTaskCompletedId)
      throws BadRequestError {
    MarkerRecordedEventAttributes marker = new MarkerRecordedEventAttributes();
    if (attr.isSetDetails()) {
      marker.setDetails(attr.getDetails());
    }
    if (!attr.isSetMarkerName()) {
      throw new BadRequestError("marker name is required");
    }
    marker.setMarkerName(attr.getMarkerName());
    marker.setDecisionTaskCompletedEventId(decisionTaskCompletedId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.MarkerRecorded)
            .setMarkerRecordedEventAttributes(marker);
    ctx.addEvent(event);
  }

  private void processCancelTimer(
      RequestContext ctx, CancelTimerDecisionAttributes d, long decisionTaskCompletedId)
      throws InternalServiceError, BadRequestError {
    String timerId = d.getTimerId();
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer == null) {
      CancelTimerFailedEventAttributes failedAttr =
          new CancelTimerFailedEventAttributes()
              .setTimerId(timerId)
              .setCause("TIMER_ID_UNKNOWN")
              .setDecisionTaskCompletedEventId(decisionTaskCompletedId);
      HistoryEvent cancellationFailed =
          new HistoryEvent()
              .setEventType(EventType.CancelTimerFailed)
              .setCancelTimerFailedEventAttributes(failedAttr);
      ctx.addEvent(cancellationFailed);
      return;
    }
    timer.action(StateMachines.Action.CANCEL, ctx, d, decisionTaskCompletedId);
    timers.remove(timerId);
  }

  private void processRequestCancelActivityTask(
      RequestContext ctx,
      RequestCancelActivityTaskDecisionAttributes a,
      long decisionTaskCompletedId)
      throws InternalServiceError, BadRequestError {
    String activityId = a.getActivityId();
    StateMachine<?> activity = activities.get(activityId);
    if (activity == null) {
      RequestCancelActivityTaskFailedEventAttributes failedAttr =
          new RequestCancelActivityTaskFailedEventAttributes()
              .setActivityId(activityId)
              .setCause("ACTIVITY_ID_UNKNOWN")
              .setDecisionTaskCompletedEventId(decisionTaskCompletedId);
      HistoryEvent cancellationFailed =
          new HistoryEvent()
              .setEventType(EventType.RequestCancelActivityTaskFailed)
              .setRequestCancelActivityTaskFailedEventAttributes(failedAttr);
      ctx.addEvent(cancellationFailed);
      return;
    }
    State beforeState = activity.getState();
    activity.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, a, decisionTaskCompletedId);
    if (beforeState == StateMachines.State.INITIATED) {
      activity.action(StateMachines.Action.CANCEL, ctx, null, 0);
      activities.remove(activityId);
      ctx.setNeedDecision(true);
    }
  }

  private void processScheduleActivityTask(
      RequestContext ctx, ScheduleActivityTaskDecisionAttributes a, long decisionTaskCompletedId)
      throws BadRequestError, InternalServiceError {
    validateScheduleActivityTask(a);
    String activityId = a.getActivityId();
    StateMachine<ActivityTaskData> activity = activities.get(activityId);
    if (activity != null) {
      throw new BadRequestError("Already open activity with " + activityId);
    }
    activity = StateMachines.newActivityStateMachine();
    activities.put(activityId, activity);
    activity.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ctx.addTimer(
        a.getScheduleToCloseTimeoutSeconds(),
        () -> timeoutActivity(activityId, TimeoutType.SCHEDULE_TO_CLOSE),
        "Activity ScheduleToCloseTimeout");
    ctx.addTimer(
        a.getScheduleToStartTimeoutSeconds(),
        () -> timeoutActivity(activityId, TimeoutType.SCHEDULE_TO_START),
        "Activity ScheduleToStartTimeout");
    ctx.lockTimer();
  }

  private void validateScheduleActivityTask(ScheduleActivityTaskDecisionAttributes a)
      throws BadRequestError {
    if (a == null) {
      throw new BadRequestError("ScheduleActivityTaskDecisionAttributes is not set on decision.");
    }

    if (a.getTaskList() == null || a.getTaskList().getName().isEmpty()) {
      throw new BadRequestError("TaskList is not set on decision.");
    }
    if (a.getActivityId() == null || a.getActivityId().isEmpty()) {
      throw new BadRequestError("ActivityId is not set on decision.");
    }
    if (a.getActivityType() == null
        || a.getActivityType().getName() == null
        || a.getActivityType().getName().isEmpty()) {
      throw new BadRequestError("ActivityType is not set on decision.");
    }
    if (a.getStartToCloseTimeoutSeconds() <= 0) {
      throw new BadRequestError("A valid StartToCloseTimeoutSeconds is not set on decision.");
    }
    if (a.getScheduleToStartTimeoutSeconds() <= 0) {
      throw new BadRequestError("A valid ScheduleToStartTimeoutSeconds is not set on decision.");
    }
    if (a.getScheduleToCloseTimeoutSeconds() <= 0) {
      throw new BadRequestError("A valid ScheduleToCloseTimeoutSeconds is not set on decision.");
    }
    if (a.getHeartbeatTimeoutSeconds() < 0) {
      throw new BadRequestError("Ac valid HeartbeatTimeoutSeconds is not set on decision.");
    }
  }

  private void processStartChildWorkflow(
      RequestContext ctx,
      StartChildWorkflowExecutionDecisionAttributes a,
      long decisionTaskCompletedId)
      throws BadRequestError, InternalServiceError {
    validateStartChildWorkflow(a);
    StateMachine<ChildWorkflowData> child = StateMachines.newChildWorkflowStateMachine(service);
    childWorkflows.put(ctx.getNextEventId(), child);
    child.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ctx.lockTimer();
  }

  // TODO
  private void validateStartChildWorkflow(StartChildWorkflowExecutionDecisionAttributes a) {}

  private void processSignalExternalWorkflowExecution(
      RequestContext ctx,
      SignalExternalWorkflowExecutionDecisionAttributes a,
      long decisionTaskCompletedId)
      throws InternalServiceError, BadRequestError {
    String signalId = UUID.randomUUID().toString();
    StateMachine<SignalExternalData> signalStateMachine =
        StateMachines.newSignalExternalStateMachine();
    externalSignals.put(signalId, signalStateMachine);
    signalStateMachine.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              try {
                service.signalExternalWorkflowExecution(signalId, a, this);
              } catch (Exception e) {
                log.error("Failure signalling an external workflow execution", e);
              }
            });
    ctx.lockTimer();
  }

  @Override
  public void completeSignalExternalWorkflowExecution(String signalId, String runId)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          StateMachine<SignalExternalData> signal = getSignal(signalId);
          signal.action(Action.COMPLETE, ctx, runId, 0);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void failSignalExternalWorkflowExecution(
      String signalId, SignalExternalWorkflowExecutionFailedCause cause)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          StateMachine<SignalExternalData> signal = getSignal(signalId);
          signal.action(Action.FAIL, ctx, cause, 0);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  private StateMachine<SignalExternalData> getSignal(String signalId) throws EntityNotExistsError {
    StateMachine<SignalExternalData> signal = externalSignals.get(signalId);
    if (signal == null) {
      throw new EntityNotExistsError("unknown signalId: " + signalId);
    }
    return signal;
  }

  // TODO: insert a single decision failure into the history
  @Override
  public void failDecisionTask(RespondDecisionTaskFailedRequest request)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    completeDecisionUpdate(
        ctx -> {
          decision.action(Action.FAIL, ctx, request, 0);
          scheduleDecision(ctx);
        },
        null); // reset sticky attributes to null
  }

  // TODO: insert a single decision timeout into the history
  private void timeoutDecisionTask(long scheduledEventId) {
    try {
      completeDecisionUpdate(
          ctx -> {
            if (decision == null || decision.getData().scheduledEventId != scheduledEventId) {
              // timeout for a previous decision
              return;
            }
            decision.action(StateMachines.Action.TIME_OUT, ctx, TimeoutType.START_TO_CLOSE, 0);
            scheduleDecision(ctx);
          },
          null); // reset sticky attributes to null
    } catch (EntityNotExistsError e) {
      // Expected as timers are not removed
    } catch (Exception e) {
      // Cannot fail to timer threads
      log.error("Failure trying to timeout a decision scheduledEventId=" + scheduledEventId, e);
    }
  }

  @Override
  public void childWorkflowStarted(ChildWorkflowExecutionStartedEventAttributes a)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(StateMachines.Action.START, ctx, a, 0);
          scheduleDecision(ctx);
          // No need to lock until completion as child workflow might skip
          // time as well
          ctx.unlockTimer();
        });
  }

  @Override
  public void childWorklfowFailed(String activityId, ChildWorkflowExecutionFailedEventAttributes a)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(StateMachines.Action.FAIL, ctx, a, 0);
          childWorkflows.remove(a.getInitiatedEventId());
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void childWorklfowTimedOut(
      String activityId, ChildWorkflowExecutionTimedOutEventAttributes a)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(Action.TIME_OUT, ctx, a.getTimeoutType(), 0);
          childWorkflows.remove(a.getInitiatedEventId());
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void failStartChildWorkflow(
      String childId, StartChildWorkflowExecutionFailedEventAttributes a)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(StateMachines.Action.FAIL, ctx, a, 0);
          childWorkflows.remove(a.getInitiatedEventId());
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void childWorkflowCompleted(
      String activityId, ChildWorkflowExecutionCompletedEventAttributes a)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(StateMachines.Action.COMPLETE, ctx, a, 0);
          childWorkflows.remove(a.getInitiatedEventId());
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void childWorkflowCanceled(
      String activityId, ChildWorkflowExecutionCanceledEventAttributes a)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(StateMachines.Action.CANCEL, ctx, a, 0);
          childWorkflows.remove(a.getInitiatedEventId());
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  private void processStartTimer(
      RequestContext ctx, StartTimerDecisionAttributes a, long decisionTaskCompletedId)
      throws BadRequestError, InternalServiceError {
    String timerId = a.getTimerId();
    if (timerId == null) {
      throw new BadRequestError("A valid TimerId is not set on StartTimerDecision");
    }
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer != null) {
      throw new BadRequestError("Already open timer with " + timerId);
    }
    timer = StateMachines.newTimerStateMachine();
    timers.put(timerId, timer);
    timer.action(StateMachines.Action.START, ctx, a, decisionTaskCompletedId);
    ctx.addTimer(a.getStartToFireTimeoutSeconds(), () -> fireTimer(timerId), "fire timer");
  }

  private void fireTimer(String timerId) {
    StateMachine<TimerData> timer;
    lock.lock();
    try {
      {
        timer = timers.get(timerId);
        if (timer == null || workflow.getState() != State.STARTED) {
          return; // cancelled already
        }
      }
    } finally {
      lock.unlock();
    }
    try {
      update(
          ctx -> {
            timer.action(StateMachines.Action.COMPLETE, ctx, null, 0);
            timers.remove(timerId);
            scheduleDecision(ctx);
          });
    } catch (BadRequestError | InternalServiceError | EntityNotExistsError e) {
      // Cannot fail to timer threads
      log.error("Failure firing a timer", e);
    }
  }

  private void processFailWorkflowExecution(
      RequestContext ctx, FailWorkflowExecutionDecisionAttributes d, long decisionTaskCompletedId)
      throws InternalServiceError, BadRequestError {
    workflow.action(StateMachines.Action.FAIL, ctx, d, decisionTaskCompletedId);
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionFailedEventAttributes a =
          new ChildWorkflowExecutionFailedEventAttributes()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setDetails(d.getDetails())
              .setReason(d.getReason())
              .setWorkflowType(startRequest.getWorkflowType())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution());
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent
                      .get()
                      .childWorklfowFailed(ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
                } catch (EntityNotExistsError entityNotExistsError) {
                  // Parent might already close
                } catch (BadRequestError | InternalServiceError e) {
                  log.error("Failure reporting child completion", e);
                }
              });
    }
  }

  private void processCompleteWorkflowExecution(
      RequestContext ctx,
      CompleteWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedId)
      throws InternalServiceError, BadRequestError {
    workflow.action(StateMachines.Action.COMPLETE, ctx, d, decisionTaskCompletedId);
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionCompletedEventAttributes a =
          new ChildWorkflowExecutionCompletedEventAttributes()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setResult(d.getResult())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution())
              .setWorkflowType(startRequest.getWorkflowType());
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent
                      .get()
                      .childWorkflowCompleted(
                          ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
                } catch (EntityNotExistsError entityNotExistsError) {
                  // Parent might already close
                } catch (BadRequestError | InternalServiceError e) {
                  log.error("Failure reporting child completion", e);
                }
              });
    }
  }

  private void processCancelWorkflowExecution(
      RequestContext ctx, CancelWorkflowExecutionDecisionAttributes d, long decisionTaskCompletedId)
      throws InternalServiceError, BadRequestError {
    workflow.action(StateMachines.Action.CANCEL, ctx, d, decisionTaskCompletedId);
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionCanceledEventAttributes a =
          new ChildWorkflowExecutionCanceledEventAttributes()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setDetails(d.getDetails())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution())
              .setWorkflowType(startRequest.getWorkflowType());
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent
                      .get()
                      .childWorkflowCanceled(
                          ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
                } catch (EntityNotExistsError entityNotExistsError) {
                  // Parent might already close
                } catch (BadRequestError | InternalServiceError e) {
                  log.error("Failure reporting child completion", e);
                }
              });
    }
  }

  private void processContinueAsNewWorkflowExecution(
      RequestContext ctx,
      ContinueAsNewWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedId,
      String identity)
      throws InternalServiceError, BadRequestError {
    workflow.action(Action.CONTINUE_AS_NEW, ctx, d, decisionTaskCompletedId);
    HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
    String runId =
        service.continueAsNew(
            startRequest,
            event.getWorkflowExecutionContinuedAsNewEventAttributes(),
            identity,
            getExecutionId(),
            parent,
            parentChildInitiatedEventId);
    event.getWorkflowExecutionContinuedAsNewEventAttributes().setNewExecutionRunId(runId);
  }

  @Override
  public void startWorkflow() throws InternalServiceError, BadRequestError {
    try {
      update(
          ctx -> {
            workflow.action(StateMachines.Action.START, ctx, startRequest, 0);
            scheduleDecision(ctx);
            ctx.addTimer(
                startRequest.getExecutionStartToCloseTimeoutSeconds(),
                this::timeoutWorkflow,
                "workflow execution timeout");
          });
    } catch (EntityNotExistsError entityNotExistsError) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(entityNotExistsError));
    }
    if (parent.isPresent()) {
      ChildWorkflowExecutionStartedEventAttributes a =
          new ChildWorkflowExecutionStartedEventAttributes()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setWorkflowExecution(getExecutionId().getExecution())
              .setDomain(getExecutionId().getDomain())
              .setWorkflowType(startRequest.getWorkflowType());
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent.get().childWorkflowStarted(a);
                } catch (EntityNotExistsError entityNotExistsError) {
                  // Not a problem. Parent might just close by now.
                } catch (BadRequestError | InternalServiceError e) {
                  log.error("Failure reporting child completion", e);
                }
              });
    }
  }

  private void scheduleDecision(RequestContext ctx) throws InternalServiceError, BadRequestError {
    if (decision != null) {
      if (decision.getState() == StateMachines.State.INITIATED) {
        return; // No need to schedule again
      }
      if (decision.getState() == StateMachines.State.STARTED) {
        ctx.setNeedDecision(true);
        return;
      }
      if (decision.getState() == StateMachines.State.FAILED
          || decision.getState() == StateMachines.State.COMPLETED
          || decision.getState() == State.TIMED_OUT) {
        decision.action(StateMachines.Action.INITIATE, ctx, startRequest, 0);
        ctx.lockTimer();
        return;
      }
      throw new InternalServiceError("unexpected decision state: " + decision.getState());
    }
    this.decision = StateMachines.newDecisionStateMachine(lastNonFailedDecisionStartEventId, store);
    decision.action(StateMachines.Action.INITIATE, ctx, startRequest, 0);
    ctx.lockTimer();
  }

  @Override
  public void startActivityTask(
      PollForActivityTaskResponse task, PollForActivityTaskRequest pollRequest)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          String activityId = task.getActivityId();
          StateMachine<ActivityTaskData> activity = getActivity(activityId);
          activity.action(StateMachines.Action.START, ctx, pollRequest, 0);
          ActivityTaskData data = activity.getData();
          int startToCloseTimeout = data.scheduledEvent.getStartToCloseTimeoutSeconds();
          int heartbeatTimeout = data.scheduledEvent.getHeartbeatTimeoutSeconds();
          if (startToCloseTimeout > 0) {
            ctx.addTimer(
                startToCloseTimeout,
                () -> timeoutActivity(activityId, TimeoutType.START_TO_CLOSE),
                "Activity StartToCloseTimeout");
          }
          updateHeartbeatTimer(ctx, activityId, activity, startToCloseTimeout, heartbeatTimeout);
        });
  }

  private void checkCompleted() throws EntityNotExistsError {
    State workflowState = workflow.getState();
    if (isTerminalState(workflowState)) {
      throw new EntityNotExistsError("Workflow is already completed: " + workflowState);
    }
  }

  private boolean isTerminalState(State workflowState) {
    return workflowState == State.COMPLETED
        || workflowState == State.TIMED_OUT
        || workflowState == State.FAILED
        || workflowState == State.CANCELED;
  }

  private void updateHeartbeatTimer(
      RequestContext ctx,
      String activityId,
      StateMachine<ActivityTaskData> activity,
      int startToCloseTimeout,
      int heartbeatTimeout) {
    if (heartbeatTimeout > 0 && heartbeatTimeout < startToCloseTimeout) {
      activity.getData().lastHeartbeatTime = clock.getAsLong();
      ctx.addTimer(
          heartbeatTimeout,
          () -> timeoutActivity(activityId, TimeoutType.HEARTBEAT),
          "Activity Heartbeat Timeout");
    }
  }

  @Override
  public void completeActivityTask(String activityId, RespondActivityTaskCompletedRequest request)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(activityId);
          activity.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          activities.remove(activityId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void completeActivityTaskById(
      String activityId, RespondActivityTaskCompletedByIDRequest request)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(activityId);
          activity.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          activities.remove(activityId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void failActivityTask(String activityId, RespondActivityTaskFailedRequest request)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(activityId);
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          activities.remove(activityId);
          ctx.unlockTimer();
          scheduleDecision(ctx);
        });
  }

  @Override
  public void failActivityTaskById(String activityId, RespondActivityTaskFailedByIDRequest request)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(activityId);
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          activities.remove(activityId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void cancelActivityTask(String activityId, RespondActivityTaskCanceledRequest request)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(activityId);
          activity.action(StateMachines.Action.CANCEL, ctx, request, 0);
          activities.remove(activityId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void cancelActivityTaskById(
      String activityId, RespondActivityTaskCanceledByIDRequest request)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(activityId);
          activity.action(StateMachines.Action.CANCEL, ctx, request, 0);
          activities.remove(activityId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public RecordActivityTaskHeartbeatResponse heartbeatActivityTask(
      String activityId, RecordActivityTaskHeartbeatRequest request)
      throws InternalServiceError, EntityNotExistsError {
    RecordActivityTaskHeartbeatResponse result = new RecordActivityTaskHeartbeatResponse();
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity = getActivity(activityId);
            activity.action(StateMachines.Action.UPDATE, ctx, request, 0);
            if (activity.getState() == StateMachines.State.CANCELLATION_REQUESTED) {
              result.setCancelRequested(true);
            }
            ActivityTaskData data = activity.getData();
            data.lastHeartbeatTime = clock.getAsLong();
            int startToCloseTimeout = data.scheduledEvent.getStartToCloseTimeoutSeconds();
            int heartbeatTimeout = data.scheduledEvent.getHeartbeatTimeoutSeconds();
            updateHeartbeatTimer(ctx, activityId, activity, startToCloseTimeout, heartbeatTimeout);
          });

    } catch (InternalServiceError | EntityNotExistsError e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServiceError(Throwables.getStackTraceAsString(e));
    }
    return result;
  }

  private void timeoutActivity(String activityId, TimeoutType timeoutType) {
    boolean unlockTimer = true;
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity = getActivity(activityId);
            if (timeoutType == TimeoutType.SCHEDULE_TO_START
                && activity.getState() != StateMachines.State.INITIATED) {
              return;
            }
            if (timeoutType == TimeoutType.HEARTBEAT) {
              // Deal with timers which are never cancelled
              if (clock.getAsLong() - activity.getData().lastHeartbeatTime
                  < activity.getData().scheduledEvent.getHeartbeatTimeoutSeconds()
                      * MILLISECONDS_IN_SECOND) {
                return;
              }
            }
            activity.action(StateMachines.Action.TIME_OUT, ctx, timeoutType, 0);
            activities.remove(activityId);
            scheduleDecision(ctx);
          });
    } catch (EntityNotExistsError e) {
      // Expected as timers are not removed
      unlockTimer = false;
    } catch (Exception e) {
      // Cannot fail to timer threads
      log.error("Failure trying to timeout an activity", e);
    } finally {
      if (unlockTimer) {
        selfAdvancingTimer.unlockTimeSkipping("timeoutActivity " + activityId);
      }
    }
  }

  private void timeoutWorkflow() {
    lock.lock();
    try {
      {
        if (isTerminalState(workflow.getState())) {
          return;
        }
      }
    } finally {
      lock.unlock();
    }
    try {
      update(
          ctx -> {
            if (isTerminalState(workflow.getState())) {
              return;
            }
            log.info("Workflow timed out before action");
            workflow.action(StateMachines.Action.TIME_OUT, ctx, TimeoutType.START_TO_CLOSE, 0);
            log.info("Workflow timed out after action");
            if (parent != null) {
              ctx.lockTimer(); // unlocked by the parent
            }
            ForkJoinPool.commonPool().execute(() -> reportWorkflowTimeoutToParent(ctx));
          });
    } catch (BadRequestError | InternalServiceError | EntityNotExistsError e) {
      // Cannot fail to timer threads
      log.error("Failure trying to timeout a workflow", e);
    }
    log.info("Workflow timed out done");
  }

  private void reportWorkflowTimeoutToParent(RequestContext ctx) {
    if (!parent.isPresent()) {
      return;
    }
    try {
      ChildWorkflowExecutionTimedOutEventAttributes a =
          new ChildWorkflowExecutionTimedOutEventAttributes()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setTimeoutType(TimeoutType.START_TO_CLOSE)
              .setWorkflowType(startRequest.getWorkflowType())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution());
      parent.get().childWorklfowTimedOut(ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
    } catch (EntityNotExistsError entityNotExistsError) {
      // Parent might already close
    } catch (BadRequestError | InternalServiceError e) {
      log.error("Failure reporting child timing out", e);
    }
  }

  @Override
  public void signal(SignalWorkflowExecutionRequest signalRequest)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          addExecutionSignaledEvent(ctx, signalRequest);
          scheduleDecision(ctx);
        });
  }

  @Override
  public void signalFromWorkflow(SignalExternalWorkflowExecutionDecisionAttributes a)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          addExecutionSignaledByExternalEvent(ctx, a);
          scheduleDecision(ctx);
        });
  }

  @Override
  public void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest)
      throws EntityNotExistsError, InternalServiceError, BadRequestError {
    update(
        ctx -> {
          workflow.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, cancelRequest, 0);
          scheduleDecision(ctx);
        });
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryRequest) throws TException {
    QueryId queryId = new QueryId(executionId);

    PollForDecisionTaskResponse task =
        new PollForDecisionTaskResponse()
            .setTaskToken(queryId.toBytes())
            .setWorkflowExecution(executionId.getExecution())
            .setWorkflowType(startRequest.getWorkflowType())
            .setQuery(queryRequest.getQuery())
            .setWorkflowExecutionTaskList(startRequest.getTaskList());
    TaskListId taskListId =
        new TaskListId(
            queryRequest.getDomain(),
            stickyExecutionAttributes == null
                ? startRequest.getTaskList().getName()
                : stickyExecutionAttributes.getWorkerTaskList().getName());
    CompletableFuture<QueryWorkflowResponse> result = new CompletableFuture<>();
    queryRequests.put(queryId.getQueryId(), task);
    queries.put(queryId.getQueryId(), result);
    store.sendQueryTask(executionId, taskListId, task);
    try {
      return result.get();
    } catch (InterruptedException e) {
      return new QueryWorkflowResponse();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TException) {
        throw (TException) cause;
      }
      throw new InternalServiceError(Throwables.getStackTraceAsString(cause));
    }
  }

  @Override
  public void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest)
      throws EntityNotExistsError {
    CompletableFuture<QueryWorkflowResponse> result = queries.get(queryId.getQueryId());
    if (result == null) {
      throw new EntityNotExistsError("Unknown query id: " + queryId.getQueryId());
    }
    if (completeRequest.getCompletedType() == QueryTaskCompletedType.COMPLETED) {
      QueryWorkflowResponse response =
          new QueryWorkflowResponse().setQueryResult(completeRequest.getQueryResult());
      result.complete(response);
    } else if (stickyExecutionAttributes != null) {
      stickyExecutionAttributes = null;
      PollForDecisionTaskResponse task = queryRequests.remove(queryId.getQueryId());

      TaskListId taskListId =
          new TaskListId(startRequest.getDomain(), startRequest.getTaskList().getName());
      store.sendQueryTask(executionId, taskListId, task);
    } else {
      QueryFailedError error = new QueryFailedError().setMessage(completeRequest.getErrorMessage());
      result.completeExceptionally(error);
    }
  }

  private void addExecutionSignaledEvent(
      RequestContext ctx, SignalWorkflowExecutionRequest signalRequest) {
    WorkflowExecutionSignaledEventAttributes a =
        new WorkflowExecutionSignaledEventAttributes()
            .setInput(startRequest.getInput())
            .setIdentity(signalRequest.getIdentity())
            .setInput(signalRequest.getInput())
            .setSignalName(signalRequest.getSignalName());
    HistoryEvent executionSignaled =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionSignaled)
            .setWorkflowExecutionSignaledEventAttributes(a);
    ctx.addEvent(executionSignaled);
  }

  private void addExecutionSignaledByExternalEvent(
      RequestContext ctx, SignalExternalWorkflowExecutionDecisionAttributes d) {
    WorkflowExecutionSignaledEventAttributes a =
        new WorkflowExecutionSignaledEventAttributes()
            .setInput(startRequest.getInput())
            .setInput(d.getInput())
            .setSignalName(d.getSignalName());
    HistoryEvent executionSignaled =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionSignaled)
            .setWorkflowExecutionSignaledEventAttributes(a);
    ctx.addEvent(executionSignaled);
  }

  private StateMachine<ActivityTaskData> getActivity(String activityId)
      throws EntityNotExistsError {
    StateMachine<ActivityTaskData> activity = activities.get(activityId);
    if (activity == null) {
      throw new EntityNotExistsError("unknown activityId: " + activityId);
    }
    return activity;
  }

  private StateMachine<ChildWorkflowData> getChildWorkflow(long initiatedEventId)
      throws InternalServiceError {
    StateMachine<ChildWorkflowData> child = childWorkflows.get(initiatedEventId);
    if (child == null) {
      throw new InternalServiceError("unknown initiatedEventId: " + initiatedEventId);
    }
    return child;
  }

  static class QueryId {

    private final ExecutionId executionId;
    private final String queryId;

    QueryId(ExecutionId executionId) {
      this.executionId = Objects.requireNonNull(executionId);
      this.queryId = UUID.randomUUID().toString();
    }

    private QueryId(ExecutionId executionId, String queryId) {
      this.executionId = Objects.requireNonNull(executionId);
      this.queryId = queryId;
    }

    public ExecutionId getExecutionId() {
      return executionId;
    }

    String getQueryId() {
      return queryId;
    }

    byte[] toBytes() throws InternalServiceError {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bout);
      addBytes(out);
      return bout.toByteArray();
    }

    void addBytes(DataOutputStream out) throws InternalServiceError {
      try {
        executionId.addBytes(out);
        out.writeUTF(queryId);
      } catch (IOException e) {
        throw new InternalServiceError(Throwables.getStackTraceAsString(e));
      }
    }

    static QueryId fromBytes(byte[] serialized) throws InternalServiceError {
      ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
      DataInputStream in = new DataInputStream(bin);
      try {
        ExecutionId executionId = ExecutionId.readFromBytes(in);
        String queryId = in.readUTF();
        return new QueryId(executionId, queryId);
      } catch (IOException e) {
        throw new InternalServiceError(Throwables.getStackTraceAsString(e));
      }
    }
  }
}
