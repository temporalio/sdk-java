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

package io.temporal.internal.testservice;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.*;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.StateMachines.Action;
import io.temporal.internal.testservice.StateMachines.ActivityTaskData;
import io.temporal.internal.testservice.StateMachines.ChildWorkflowData;
import io.temporal.internal.testservice.StateMachines.DecisionTaskData;
import io.temporal.internal.testservice.StateMachines.SignalExternalData;
import io.temporal.internal.testservice.StateMachines.State;
import io.temporal.internal.testservice.StateMachines.TimerData;
import io.temporal.internal.testservice.StateMachines.WorkflowData;
import io.temporal.internal.testservice.TestWorkflowStore.TaskListId;
import io.temporal.serviceclient.GrpcFailure;
import io.temporal.serviceclient.GrpcStatusUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestWorkflowMutableStateImpl implements TestWorkflowMutableState {

  @FunctionalInterface
  private interface UpdateProcedure {

    void apply(RequestContext ctx);
  }

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowMutableStateImpl.class);

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

  /**
   * @param retryState present if workflow is a retry
   * @param backoffStartIntervalInSeconds
   * @param parentChildInitiatedEventId id of the child initiated event in the parent history
   */
  TestWorkflowMutableStateImpl(
      StartWorkflowExecutionRequest startRequest,
      Optional<RetryState> retryState,
      int backoffStartIntervalInSeconds,
      ByteString lastCompletionResult,
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
    WorkflowData data =
        new WorkflowData(
            retryState,
            backoffStartIntervalInSeconds,
            startRequest.getCronSchedule(),
            lastCompletionResult);
    this.workflow = StateMachines.newWorkflowStateMachine(data);
  }

  private void update(UpdateProcedure updater) {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    update(false, updater, stackTraceElements[2].getMethodName());
  }

  private void completeDecisionUpdate(
      UpdateProcedure updater, StickyExecutionAttributes attributes) {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    stickyExecutionAttributes = attributes;
    update(true, updater, stackTraceElements[2].getMethodName());
  }

  private void update(boolean completeDecisionUpdate, UpdateProcedure updater, String caller) {
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
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.INTERNAL)
          || e.getStatus().getCode().equals(Status.Code.NOT_FOUND)
          || e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)) {
        throw e;
      } else {
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(e)));
      }
    } catch (Exception e) {
      throw new StatusRuntimeException(
          Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(e)));
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
        return Optional.of(WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusFailed);
      case TIMED_OUT:
        return Optional.of(WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusTimedOut);
      case CANCELED:
        return Optional.of(WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusCanceled);
      case COMPLETED:
        return Optional.of(WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusCompleted);
      case CONTINUED_AS_NEW:
        return Optional.of(WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusContinuedAsNew);
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
      PollForDecisionTaskResponse task, PollForDecisionTaskRequest pollRequest) {
    if (task.getQuery() == null) {
      update(
          ctx -> {
            long scheduledEventId = decision.getData().scheduledEventId;
            decision.action(StateMachines.Action.START, ctx, pollRequest, 0);
            ctx.addTimer(
                startRequest.getTaskStartToCloseTimeoutSeconds(),
                () -> timeoutDecisionTask(scheduledEventId),
                "DecisionTask StartToCloseTimeout");
          });
    }
  }

  @Override
  public void completeDecisionTask(int historySize, RespondDecisionTaskCompletedRequest request) {
    List<Decision> decisions = request.getDecisionsList();
    completeDecisionUpdate(
        ctx -> {
          if (ctx.getInitialEventId() != historySize + 1) {
            throw new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription(
                    "Expired decision: expectedHistorySize="
                        + historySize
                        + ","
                        + " actualHistorySize="
                        + ctx.getInitialEventId()));
          }
          long decisionTaskCompletedId = ctx.getNextEventId() - 1;
          // Fail the decision if there are new events and the decision tries to complete the
          // workflow
          if (!concurrentToDecision.isEmpty() && hasCompleteDecision(request.getDecisionsList())) {
            RespondDecisionTaskFailedRequest failedRequest =
                RespondDecisionTaskFailedRequest.newBuilder()
                    .setCause(DecisionTaskFailedCause.DecisionTaskFailedCauseUnhandledDecision)
                    .setIdentity(request.getIdentity())
                    .build();
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
            throw new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("No outstanding decision"));
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
          if (!completed
              && ((ctx.isNeedDecision() || !this.concurrentToDecision.isEmpty())
                  || request.getForceCreateNewDecisionTask())) {
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
      RequestContext ctx, Decision d, String identity, long decisionTaskCompletedId) {
    switch (d.getDecisionType()) {
      case DecisionTypeCompleteWorkflowExecution:
        processCompleteWorkflowExecution(
            ctx,
            d.getCompleteWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId,
            identity);
        break;
      case DecisionTypeFailWorkflowExecution:
        processFailWorkflowExecution(
            ctx, d.getFailWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId, identity);
        break;
      case DecisionTypeCancelWorkflowExecution:
        processCancelWorkflowExecution(
            ctx, d.getCancelWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeContinueAsNewWorkflowExecution:
        processContinueAsNewWorkflowExecution(
            ctx,
            d.getContinueAsNewWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId,
            identity);
        break;
      case DecisionTypeScheduleActivityTask:
        processScheduleActivityTask(
            ctx, d.getScheduleActivityTaskDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeRequestCancelActivityTask:
        processRequestCancelActivityTask(
            ctx, d.getRequestCancelActivityTaskDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeStartTimer:
        processStartTimer(ctx, d.getStartTimerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeCancelTimer:
        processCancelTimer(ctx, d.getCancelTimerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeStartChildWorkflowExecution:
        processStartChildWorkflow(
            ctx, d.getStartChildWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeSignalExternalWorkflowExecution:
        processSignalExternalWorkflowExecution(
            ctx, d.getSignalExternalWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeRecordMarker:
        processRecordMarker(ctx, d.getRecordMarkerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DecisionTypeRequestCancelExternalWorkflowExecution:
        processRequestCancelExternalWorkflowExecution(
            ctx, d.getRequestCancelExternalWorkflowExecutionDecisionAttributes());
        break;
      case DecisionTypeUpsertWorkflowSearchAttributes:
        // TODO: https://github.io.temporal-java-client/issues/360
        break;
    }
  }

  private void processRequestCancelExternalWorkflowExecution(
      RequestContext ctx, RequestCancelExternalWorkflowExecutionDecisionAttributes attr) {
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              WorkflowExecution workflowExecution =
                  WorkflowExecution.newBuilder().setWorkflowId(attr.getWorkflowId()).build();
              RequestCancelWorkflowExecutionRequest request =
                  RequestCancelWorkflowExecutionRequest.newBuilder()
                      .setDomain(ctx.getDomain())
                      .setRequestId(UUID.randomUUID().toString())
                      .setWorkflowExecution(workflowExecution)
                      .build();
              try {
                service.getMockService().requestCancelWorkflowExecution(request, null);
              } catch (StatusRuntimeException e) {
                log.error("Failure to request cancel external workflow", e);
              }
            });
  }

  private void processRecordMarker(
      RequestContext ctx, RecordMarkerDecisionAttributes attr, long decisionTaskCompletedId) {
    if (!Strings.isNullOrEmpty(attr.getMarkerName())) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("marker name is required"));
    }

    MarkerRecordedEventAttributes marker =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(attr.getMarkerName())
            .setHeader(attr.getHeader())
            .setDetails(attr.getDetails())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeMarkerRecorded)
            .setMarkerRecordedEventAttributes(marker)
            .build();
    ctx.addEvent(event);
  }

  private void processCancelTimer(
      RequestContext ctx, CancelTimerDecisionAttributes d, long decisionTaskCompletedId) {
    String timerId = d.getTimerId();
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer == null) {
      CancelTimerFailedEventAttributes failedAttr =
          CancelTimerFailedEventAttributes.newBuilder()
              .setTimerId(timerId)
              .setCause("TIMER_ID_UNKNOWN")
              .setDecisionTaskCompletedEventId(decisionTaskCompletedId)
              .build();
      HistoryEvent cancellationFailed =
          HistoryEvent.newBuilder()
              .setEventType(EventType.EventTypeCancelTimerFailed)
              .setCancelTimerFailedEventAttributes(failedAttr)
              .build();
      ctx.addEvent(cancellationFailed);
      return;
    }
    timer.action(StateMachines.Action.CANCEL, ctx, d, decisionTaskCompletedId);
    timers.remove(timerId);
  }

  private void processRequestCancelActivityTask(
      RequestContext ctx,
      RequestCancelActivityTaskDecisionAttributes a,
      long decisionTaskCompletedId) {
    String activityId = a.getActivityId();
    StateMachine<?> activity = activities.get(activityId);
    if (activity == null) {
      RequestCancelActivityTaskFailedEventAttributes failedAttr =
          RequestCancelActivityTaskFailedEventAttributes.newBuilder()
              .setActivityId(activityId)
              .setCause("ACTIVITY_ID_UNKNOWN")
              .setDecisionTaskCompletedEventId(decisionTaskCompletedId)
              .build();
      HistoryEvent cancellationFailed =
          HistoryEvent.newBuilder()
              .setEventType(EventType.EventTypeRequestCancelActivityTaskFailed)
              .setRequestCancelActivityTaskFailedEventAttributes(failedAttr)
              .build();
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
      RequestContext ctx, ScheduleActivityTaskDecisionAttributes a, long decisionTaskCompletedId) {
    validateScheduleActivityTask(a);
    String activityId = a.getActivityId();
    StateMachine<ActivityTaskData> activity = activities.get(activityId);
    if (activity != null) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("Already open activity with " + activityId));
    }
    activity = StateMachines.newActivityStateMachine(store, this.startRequest);
    activities.put(activityId, activity);
    activity.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ActivityTaskScheduledEventAttributes scheduledEvent = activity.getData().scheduledEvent;
    ctx.addTimer(
        scheduledEvent.getScheduleToCloseTimeoutSeconds(),
        () -> timeoutActivity(activityId, TimeoutType.TimeoutTypeScheduleToClose),
        "Activity ScheduleToCloseTimeout");
    ctx.addTimer(
        scheduledEvent.getScheduleToStartTimeoutSeconds(),
        () -> timeoutActivity(activityId, TimeoutType.TimeoutTypeScheduleToStart),
        "Activity ScheduleToStartTimeout");
    ctx.lockTimer();
  }

  private void validateScheduleActivityTask(ScheduleActivityTaskDecisionAttributes a) {
    if (a == null) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "ScheduleActivityTaskDecisionAttributes is not set on decision."));
    }

    if (a.getTaskList() == null || a.getTaskList().getName().isEmpty()) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("TaskList is not set on decision."));
    }
    if (a.getActivityId() == null || a.getActivityId().isEmpty()) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("ActivityId is not set on decision."));
    }
    if (a.getActivityType() == null
        || a.getActivityType().getName() == null
        || a.getActivityType().getName().isEmpty()) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("ActivityType is not set on decision."));
    }
    if (a.getStartToCloseTimeoutSeconds() <= 0) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "A valid StartToCloseTimeoutSeconds is not set on decision."));
    }
    if (a.getScheduleToStartTimeoutSeconds() <= 0) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "A valid ScheduleToStartTimeoutSeconds is not set on decision."));
    }
    if (a.getScheduleToCloseTimeoutSeconds() <= 0) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "A valid ScheduleToCloseTimeoutSeconds is not set on decision."));
    }
    if (a.getHeartbeatTimeoutSeconds() < 0) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "Ac valid HeartbeatTimeoutSeconds is not set on decision."));
    }
  }

  private void processStartChildWorkflow(
      RequestContext ctx,
      StartChildWorkflowExecutionDecisionAttributes a,
      long decisionTaskCompletedId) {
    validateStartChildExecutionAttributes(a);
    StateMachine<ChildWorkflowData> child = StateMachines.newChildWorkflowStateMachine(service);
    childWorkflows.put(ctx.getNextEventId(), child);
    child.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ctx.lockTimer();
  }

  /** Clone of the validateStartChildExecutionAttributes from historyEngine.go */
  private void validateStartChildExecutionAttributes(
      StartChildWorkflowExecutionDecisionAttributes a) {
    StartChildWorkflowExecutionDecisionAttributes.Builder b = a.toBuilder();
    if (a == null) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "StartChildWorkflowExecutionDecisionAttributes is not set on decision."));
    }

    if (a.getWorkflowId().isEmpty()) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "Required field WorkflowID is not set on decision."));
    }

    if (a.getWorkflowType() == null || a.getWorkflowType().getName().isEmpty()) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "Required field WorkflowType is not set on decision."));
    }

    // Inherit tasklist from parent workflow execution if not provided on decision
    if (a.getTaskList() == null || a.getTaskList().getName().isEmpty()) {
      b.setTaskList(startRequest.getTaskList());
    }

    // Inherit workflow timeout from parent workflow execution if not provided on decision
    if (a.getExecutionStartToCloseTimeoutSeconds() <= 0) {
      b.setExecutionStartToCloseTimeoutSeconds(
          startRequest.getExecutionStartToCloseTimeoutSeconds());
    }

    // Inherit decision task timeout from parent workflow execution if not provided on decision
    if (a.getTaskStartToCloseTimeoutSeconds() <= 0) {
      b.setTaskStartToCloseTimeoutSeconds(startRequest.getTaskStartToCloseTimeoutSeconds());
    }
    a = b.build();
    RetryPolicy retryPolicy = a.getRetryPolicy();
    if (retryPolicy != null) {
      RetryState.validateRetryPolicy(retryPolicy);
    }
  }

  private void processSignalExternalWorkflowExecution(
      RequestContext ctx,
      SignalExternalWorkflowExecutionDecisionAttributes a,
      long decisionTaskCompletedId) {
    String signalId = UUID.randomUUID().toString();
    StateMachine<SignalExternalData> signalStateMachine =
        StateMachines.newSignalExternalStateMachine();
    externalSignals.put(signalId, signalStateMachine);
    signalStateMachine.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              try {
                service.getMockService().signalExternalWorkflowExecution(signalId, a, this);
              } catch (Exception e) {
                log.error("Failure signalling an external workflow execution", e);
              }
            });
    ctx.lockTimer();
  }

  @Override
  public void completeSignalExternalWorkflowExecution(String signalId, String runId) {
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
      String signalId, SignalExternalWorkflowExecutionFailedCause cause) {
    update(
        ctx -> {
          StateMachine<SignalExternalData> signal = getSignal(signalId);
          signal.action(Action.FAIL, ctx, cause, 0);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  private StateMachine<SignalExternalData> getSignal(String signalId) {
    StateMachine<SignalExternalData> signal = externalSignals.get(signalId);
    if (signal == null) {
      throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("unknown signalId: " + signalId));
    }
    return signal;
  }

  // TODO: insert a single decision failure into the history
  @Override
  public void failDecisionTask(RespondDecisionTaskFailedRequest request) {
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
            if (decision == null
                || decision.getData().scheduledEventId != scheduledEventId
                || decision.getState() == State.COMPLETED) {
              // timeout for a previous decision
              return;
            }
            decision.action(
                StateMachines.Action.TIME_OUT, ctx, TimeoutType.TimeoutTypeStartToClose, 0);
            scheduleDecision(ctx);
          },
          null); // reset sticky attributes to null
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
        // Expected as timers are not removed
      } else {
        // Cannot fail to timer threads
        log.error("Failure trying to timeout a decision scheduledEventId=" + scheduledEventId, e);
      }
    }
  }

  @Override
  public void childWorkflowStarted(ChildWorkflowExecutionStartedEventAttributes a) {
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
  public void childWorkflowFailed(
      String activityId, ChildWorkflowExecutionFailedEventAttributes a) {
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
  public void childWorkflowTimedOut(
      String activityId, ChildWorkflowExecutionTimedOutEventAttributes a) {
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
      String childId, StartChildWorkflowExecutionFailedEventAttributes a) {
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
      String activityId, ChildWorkflowExecutionCompletedEventAttributes a) {
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
      String activityId, ChildWorkflowExecutionCanceledEventAttributes a) {
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
      RequestContext ctx, StartTimerDecisionAttributes a, long decisionTaskCompletedId) {
    String timerId = a.getTimerId();
    if (timerId == null) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(
              "A valid TimerId is not set on StartTimerDecision"));
    }
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer != null) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("Already open timer with " + timerId));
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
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)
          || e.getStatus().getCode().equals(Status.Code.INTERNAL)
          || e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
        // Cannot fail to timer threads
        log.error("Failure firing a timer", e);
      }
    }
  }

  private void processFailWorkflowExecution(
      RequestContext ctx,
      FailWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedId,
      String identity) {
    WorkflowData data = workflow.getData();
    if (data.retryState.isPresent()) {
      RetryState rs = data.retryState.get();
      int backoffIntervalSeconds =
          rs.getBackoffIntervalInSeconds(d.getReason(), store.currentTimeMillis());
      if (backoffIntervalSeconds > 0) {
        ContinueAsNewWorkflowExecutionDecisionAttributes continueAsNewAttr =
            ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder()
                .setInput(startRequest.getInput())
                .setWorkflowType(startRequest.getWorkflowType())
                .setExecutionStartToCloseTimeoutSeconds(
                    startRequest.getExecutionStartToCloseTimeoutSeconds())
                .setTaskStartToCloseTimeoutSeconds(startRequest.getTaskStartToCloseTimeoutSeconds())
                .setTaskList(startRequest.getTaskList())
                .setBackoffStartIntervalInSeconds(backoffIntervalSeconds)
                .setRetryPolicy(startRequest.getRetryPolicy())
                .build();
        workflow.action(Action.CONTINUE_AS_NEW, ctx, continueAsNewAttr, decisionTaskCompletedId);
        HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
        WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewEventAttributes =
            event.getWorkflowExecutionContinuedAsNewEventAttributes();

        Optional<RetryState> continuedRetryState = Optional.of(rs.getNextAttempt());
        String runId =
            service
                .getMockService()
                .continueAsNew(
                    startRequest,
                    continuedAsNewEventAttributes,
                    continuedRetryState,
                    identity,
                    getExecutionId(),
                    parent,
                    parentChildInitiatedEventId);
        continuedAsNewEventAttributes.toBuilder().setNewExecutionRunId(runId).build();
        return;
      }
    }

    if (!Strings.isNullOrEmpty(data.cronSchedule)) {
      startNewCronRun(ctx, decisionTaskCompletedId, identity, data, data.lastCompletionResult);
      return;
    }

    workflow.action(StateMachines.Action.FAIL, ctx, d, decisionTaskCompletedId);
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionFailedEventAttributes a =
          ChildWorkflowExecutionFailedEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setDetails(d.getDetails())
              .setReason(d.getReason())
              .setWorkflowType(startRequest.getWorkflowType())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution())
              .build();
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent
                      .get()
                      .childWorkflowFailed(ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
                } catch (StatusRuntimeException e) {
                  if (e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)
                      || e.getStatus().getCode().equals(Status.Code.INTERNAL)) {
                    log.error("Failure reporting child completion", e);
                  } else if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                    // Parent might already close
                  }
                }
              });
    }
  }

  private void processCompleteWorkflowExecution(
      RequestContext ctx,
      CompleteWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedId,
      String identity) {
    WorkflowData data = workflow.getData();
    if (!Strings.isNullOrEmpty(data.cronSchedule)) {
      startNewCronRun(ctx, decisionTaskCompletedId, identity, data, d.getResult());
      return;
    }

    workflow.action(StateMachines.Action.COMPLETE, ctx, d, decisionTaskCompletedId);
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionCompletedEventAttributes a =
          ChildWorkflowExecutionCompletedEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setResult(d.getResult())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution())
              .setWorkflowType(startRequest.getWorkflowType())
              .build();
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent
                      .get()
                      .childWorkflowCompleted(
                          ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
                } catch (StatusRuntimeException e) {
                  if (e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)
                      || e.getStatus().getCode().equals(Status.Code.INTERNAL)) {
                    log.error("Failure reporting child completion", e);
                  } else if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                    // Parent might already close
                  }
                }
              });
    }
  }

  private void startNewCronRun(
      RequestContext ctx,
      long decisionTaskCompletedId,
      String identity,
      WorkflowData data,
      ByteString lastCompletionResult) {
    CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    CronParser parser = new CronParser(cronDefinition);
    Cron cron = parser.parse(data.cronSchedule);

    Instant i = Instant.ofEpochMilli(store.currentTimeMillis());
    ZonedDateTime now = ZonedDateTime.ofInstant(i, ZoneOffset.UTC);

    ExecutionTime executionTime = ExecutionTime.forCron(cron);
    Optional<Duration> backoff = executionTime.timeToNextExecution(now);
    int backoffIntervalSeconds = (int) backoff.get().getSeconds();

    if (backoffIntervalSeconds == 0) {
      backoff = executionTime.timeToNextExecution(now.plusSeconds(1));
      backoffIntervalSeconds = (int) backoff.get().getSeconds() + 1;
    }

    ContinueAsNewWorkflowExecutionDecisionAttributes continueAsNewAttr =
        ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setWorkflowType(startRequest.getWorkflowType())
            .setExecutionStartToCloseTimeoutSeconds(
                startRequest.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(startRequest.getTaskStartToCloseTimeoutSeconds())
            .setTaskList(startRequest.getTaskList())
            .setBackoffStartIntervalInSeconds(backoffIntervalSeconds)
            .setRetryPolicy(startRequest.getRetryPolicy())
            .setLastCompletionResult(lastCompletionResult)
            .build();
    workflow.action(Action.CONTINUE_AS_NEW, ctx, continueAsNewAttr, decisionTaskCompletedId);
    HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
    WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewEventAttributes =
        event.getWorkflowExecutionContinuedAsNewEventAttributes();

    String runId =
        service
            .getMockService()
            .continueAsNew(
                startRequest,
                continuedAsNewEventAttributes,
                Optional.empty(),
                identity,
                getExecutionId(),
                parent,
                parentChildInitiatedEventId);
    continuedAsNewEventAttributes.toBuilder().setNewExecutionRunId(runId).build();
  }

  private void processCancelWorkflowExecution(
      RequestContext ctx,
      CancelWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedId) {
    workflow.action(StateMachines.Action.CANCEL, ctx, d, decisionTaskCompletedId);
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionCanceledEventAttributes a =
          ChildWorkflowExecutionCanceledEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setDetails(d.getDetails())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution())
              .setWorkflowType(startRequest.getWorkflowType())
              .build();
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent
                      .get()
                      .childWorkflowCanceled(
                          ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
                } catch (StatusRuntimeException e) {
                  if (e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)
                      || e.getStatus().getCode().equals(Status.Code.INTERNAL)) {
                    log.error("Failure reporting child completion", e);
                  } else if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                    // Parent might already close
                  }
                }
              });
    }
  }

  private void processContinueAsNewWorkflowExecution(
      RequestContext ctx,
      ContinueAsNewWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedId,
      String identity) {
    workflow.action(Action.CONTINUE_AS_NEW, ctx, d, decisionTaskCompletedId);
    HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
    String runId =
        service
            .getMockService()
            .continueAsNew(
                startRequest,
                event.getWorkflowExecutionContinuedAsNewEventAttributes(),
                workflow.getData().retryState,
                identity,
                getExecutionId(),
                parent,
                parentChildInitiatedEventId);
    event
        .getWorkflowExecutionContinuedAsNewEventAttributes()
        .toBuilder()
        .setNewExecutionRunId(runId)
        .build();
  }

  @Override
  public void startWorkflow(
      boolean continuedAsNew, Optional<SignalWorkflowExecutionRequest> signalWithStartSignal) {
    try {
      update(
          ctx -> {
            workflow.action(StateMachines.Action.START, ctx, startRequest, 0);
            if (signalWithStartSignal.isPresent()) {
              addExecutionSignaledEvent(ctx, signalWithStartSignal.get());
            }
            int backoffStartIntervalInSeconds = workflow.getData().backoffStartIntervalInSeconds;
            if (backoffStartIntervalInSeconds > 0) {
              ctx.addTimer(
                  backoffStartIntervalInSeconds,
                  () -> {
                    try {
                      update(ctx1 -> scheduleDecision(ctx1));
                    } catch (StatusRuntimeException e) {
                      if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                        // Expected as timers are not removed
                      } else {
                        // Cannot fail to timer threads
                        log.error("Failure trying to add task for an delayed workflow retry", e);
                      }
                    }
                  },
                  "delayedFirstDecision");
            } else {
              scheduleDecision(ctx);
            }

            int executionTimeoutTimerDelay = startRequest.getExecutionStartToCloseTimeoutSeconds();
            if (backoffStartIntervalInSeconds > 0) {
              executionTimeoutTimerDelay =
                  executionTimeoutTimerDelay + backoffStartIntervalInSeconds;
            }
            ctx.addTimer(
                executionTimeoutTimerDelay, this::timeoutWorkflow, "workflow execution timeout");
          });
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(e)));
      }
    }
    if (!continuedAsNew && parent.isPresent()) {
      ChildWorkflowExecutionStartedEventAttributes a =
          ChildWorkflowExecutionStartedEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setWorkflowExecution(getExecutionId().getExecution())
              .setDomain(getExecutionId().getDomain())
              .setWorkflowType(startRequest.getWorkflowType())
              .build();
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent.get().childWorkflowStarted(a);
                } catch (StatusRuntimeException e) {
                  if (e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)
                      || e.getStatus().getCode().equals(Status.Code.INTERNAL)) {
                    log.error("Failure reporting child completion", e);
                  } else if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
                    // Parent might already close
                  }
                }
              });
    }
  }

  private void scheduleDecision(RequestContext ctx) {
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
      throw new StatusRuntimeException(
          Status.INTERNAL.withDescription("unexpected decision state: " + decision.getState()));
    }
    this.decision = StateMachines.newDecisionStateMachine(lastNonFailedDecisionStartEventId, store);
    decision.action(StateMachines.Action.INITIATE, ctx, startRequest, 0);
    ctx.lockTimer();
  }

  @Override
  public void startActivityTask(
      PollForActivityTaskResponse task, PollForActivityTaskRequest pollRequest) {
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
                () -> timeoutActivity(activityId, TimeoutType.TimeoutTypeStartToClose),
                "Activity StartToCloseTimeout");
          }
          updateHeartbeatTimer(ctx, activityId, activity, startToCloseTimeout, heartbeatTimeout);
        });
  }

  private void checkCompleted() {
    State workflowState = workflow.getState();
    if (isTerminalState(workflowState)) {
      throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("Workflow is already completed: " + workflowState));
    }
  }

  private boolean isTerminalState(State workflowState) {
    return workflowState == State.COMPLETED
        || workflowState == State.TIMED_OUT
        || workflowState == State.FAILED
        || workflowState == State.CANCELED
        || workflowState == State.CONTINUED_AS_NEW;
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
          () -> timeoutActivity(activityId, TimeoutType.TimeoutTypeHeartbeat),
          "Activity Heartbeat Timeout");
    }
  }

  @Override
  public void completeActivityTask(String activityId, RespondActivityTaskCompletedRequest request) {
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
      String activityId, RespondActivityTaskCompletedByIDRequest request) {
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
  public void failActivityTask(String activityId, RespondActivityTaskFailedRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivity(activityId);
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          if (isTerminalState(activity.getState())) {
            activities.remove(activityId);
            scheduleDecision(ctx);
          } else {
            addActivityRetryTimer(ctx, activity);
          }
          // Allow time skipping when waiting for retry
          ctx.unlockTimer();
        });
  }

  private void addActivityRetryTimer(RequestContext ctx, StateMachine<ActivityTaskData> activity) {
    ActivityTaskData data = activity.getData();
    int attempt = data.retryState.getAttempt();
    ctx.addTimer(
        data.nextBackoffIntervalSeconds,
        () -> {
          // Timers are not removed, so skip if it is not for this attempt.
          if (activity.getState() != State.INITIATED && data.retryState.getAttempt() != attempt) {
            return;
          }
          selfAdvancingTimer.lockTimeSkipping(
              "activityRetryTimer " + activity.getData().scheduledEvent.getActivityId());
          boolean unlockTimer = false;
          try {
            update(ctx1 -> ctx1.addActivityTask(data.activityTask));
          } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
              // Expected as timers are not removed
              unlockTimer = true;
            } else {
              unlockTimer = true;
              // Cannot fail to timer threads
              log.error("Failure trying to add task for an activity retry", e);
            }
          } finally {
            if (unlockTimer) {
              // Allow time skipping when waiting for an activity retry
              selfAdvancingTimer.unlockTimeSkipping(
                  "activityRetryTimer " + activity.getData().scheduledEvent.getActivityId());
            }
          }
        },
        "Activity Retry");
  }

  @Override
  public void failActivityTaskById(
      String activityId, RespondActivityTaskFailedByIDRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivity(activityId);
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          if (isTerminalState(activity.getState())) {
            activities.remove(activityId);
            scheduleDecision(ctx);
          } else {
            addActivityRetryTimer(ctx, activity);
          }
          ctx.unlockTimer();
        });
  }

  @Override
  public void cancelActivityTask(String activityId, RespondActivityTaskCanceledRequest request) {
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
      String activityId, RespondActivityTaskCanceledByIDRequest request) {
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
      String activityId, byte[] details) {
    RecordActivityTaskHeartbeatResponse.Builder result =
        RecordActivityTaskHeartbeatResponse.newBuilder();
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity = getActivity(activityId);
            activity.action(StateMachines.Action.UPDATE, ctx, details, 0);
            if (activity.getState() == StateMachines.State.CANCELLATION_REQUESTED) {
              result.setCancelRequested(true);
            }
            ActivityTaskData data = activity.getData();
            data.lastHeartbeatTime = clock.getAsLong();
            int startToCloseTimeout = data.scheduledEvent.getStartToCloseTimeoutSeconds();
            int heartbeatTimeout = data.scheduledEvent.getHeartbeatTimeoutSeconds();
            updateHeartbeatTimer(ctx, activityId, activity, startToCloseTimeout, heartbeatTimeout);
          });

    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)
          || e.getStatus().getCode().equals(Status.Code.INTERNAL)) {
        throw e;
      } else {
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(e)));
      }
    }
    return result.build();
  }

  private void timeoutActivity(String activityId, TimeoutType timeoutType) {
    boolean unlockTimer = true;
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity = getActivity(activityId);
            if (timeoutType == TimeoutType.TimeoutTypeScheduleToStart
                && activity.getState() != StateMachines.State.INITIATED) {
              throw new StatusRuntimeException(
                  Status.NOT_FOUND.withDescription("Not in INITIATED"));
            }
            if (timeoutType == TimeoutType.TimeoutTypeHeartbeat) {
              // Deal with timers which are never cancelled
              long heartbeatTimeout =
                  TimeUnit.SECONDS.toMillis(
                      activity.getData().scheduledEvent.getHeartbeatTimeoutSeconds());
              if (clock.getAsLong() - activity.getData().lastHeartbeatTime < heartbeatTimeout) {
                throw new StatusRuntimeException(
                    Status.NOT_FOUND.withDescription("Not heartbeat timeout"));
              }
            }
            activity.action(StateMachines.Action.TIME_OUT, ctx, timeoutType, 0);
            if (isTerminalState(activity.getState())) {
              activities.remove(activityId);
              scheduleDecision(ctx);
            } else {
              addActivityRetryTimer(ctx, activity);
            }
          });
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
        // Expected as timers are not removed
        unlockTimer = false;
      } else {
        // Cannot fail to timer threads
        log.error("Failure trying to timeout an activity", e);
      }
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
            workflow.action(
                StateMachines.Action.TIME_OUT, ctx, TimeoutType.TimeoutTypeStartToClose, 0);
            if (parent != null) {
              ctx.lockTimer(); // unlocked by the parent
            }
            ForkJoinPool.commonPool().execute(() -> reportWorkflowTimeoutToParent(ctx));
          });
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.INTERNAL)
          || e.getStatus().getCode().equals(Status.Code.NOT_FOUND)
          || e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)) {
        // Cannot fail to timer threads
        log.error("Failure trying to timeout a workflow", e);
      }
    }
  }

  private void reportWorkflowTimeoutToParent(RequestContext ctx) {
    if (!parent.isPresent()) {
      return;
    }
    try {
      ChildWorkflowExecutionTimedOutEventAttributes a =
          ChildWorkflowExecutionTimedOutEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setTimeoutType(TimeoutType.TimeoutTypeStartToClose)
              .setWorkflowType(startRequest.getWorkflowType())
              .setDomain(ctx.getDomain())
              .setWorkflowExecution(ctx.getExecution())
              .build();
      parent.get().childWorkflowTimedOut(ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.INVALID_ARGUMENT)
          || e.getStatus().getCode().equals(Status.Code.INTERNAL)) {
        log.error("Failure reporting child completion", e);
      } else if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
        // Parent might already close
      }
    }
  }

  @Override
  public void signal(SignalWorkflowExecutionRequest signalRequest) {
    update(
        ctx -> {
          addExecutionSignaledEvent(ctx, signalRequest);
          scheduleDecision(ctx);
        });
  }

  @Override
  public void signalFromWorkflow(SignalExternalWorkflowExecutionDecisionAttributes a) {
    update(
        ctx -> {
          addExecutionSignaledByExternalEvent(ctx, a);
          scheduleDecision(ctx);
        });
  }

  @Override
  public void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest) {
    update(
        ctx -> {
          workflow.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, cancelRequest, 0);
          scheduleDecision(ctx);
        });
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryRequest) {
    QueryId queryId = new QueryId(executionId);

    Optional<WorkflowExecutionCloseStatus> optCloseStatus = getCloseStatus();
    if (optCloseStatus.isPresent() && queryRequest.getQueryRejectCondition() != null) {
      WorkflowExecutionCloseStatus closeStatus = optCloseStatus.get();
      boolean rejectNotOpen =
          queryRequest.getQueryRejectCondition()
              == QueryRejectCondition.QueryRejectConditionNotOpen;
      boolean rejectNotCompletedCleanly =
          queryRequest.getQueryRejectCondition()
                  == QueryRejectCondition.QueryRejectConditionNotCompletedCleanly
              && closeStatus != WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusCompleted;
      if (rejectNotOpen || rejectNotCompletedCleanly) {
        return QueryWorkflowResponse.newBuilder()
            .setQueryRejected(QueryRejected.newBuilder().setCloseStatus(closeStatus).build())
            .build();
      }
    }

    PollForDecisionTaskResponse task =
        PollForDecisionTaskResponse.newBuilder()
            .setTaskToken(ByteString.copyFrom(queryId.toBytes()))
            .setWorkflowExecution(executionId.getExecution())
            .setWorkflowType(startRequest.getWorkflowType())
            .setQuery(queryRequest.getQuery())
            .setWorkflowExecutionTaskList(startRequest.getTaskList())
            .build();
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
      return QueryWorkflowResponse.getDefaultInstance();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof StatusRuntimeException) {
        throw (StatusRuntimeException) cause;
      }
      throw new StatusRuntimeException(
          Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(cause)));
    }
  }

  @Override
  public void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest) {
    CompletableFuture<QueryWorkflowResponse> result = queries.get(queryId.getQueryId());
    if (result == null) {
      throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("Unknown query id: " + queryId.getQueryId()));
    }
    if (completeRequest.getCompletedType()
        == QueryTaskCompletedType.QueryTaskCompletedTypeCompleted) {
      QueryWorkflowResponse response =
          QueryWorkflowResponse.newBuilder()
              .setQueryResult(completeRequest.getQueryResult())
              .build();
      result.complete(response);
    } else if (stickyExecutionAttributes != null) {
      stickyExecutionAttributes = null;
      PollForDecisionTaskResponse task = queryRequests.remove(queryId.getQueryId());

      TaskListId taskListId =
          new TaskListId(startRequest.getDomain(), startRequest.getTaskList().getName());
      store.sendQueryTask(executionId, taskListId, task);
    } else {
      StatusRuntimeException e =
          new StatusRuntimeException(
              Status.INVALID_ARGUMENT.withDescription(completeRequest.getErrorMessage()));
      GrpcStatusUtils.setFailure(e, GrpcFailure.QUERY_FAILED, QueryFailed.getDefaultInstance());
      result.completeExceptionally(e);
    }
  }

  private void addExecutionSignaledEvent(
      RequestContext ctx, SignalWorkflowExecutionRequest signalRequest) {
    WorkflowExecutionSignaledEventAttributes a =
        WorkflowExecutionSignaledEventAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setIdentity(signalRequest.getIdentity())
            .setInput(signalRequest.getInput())
            .setSignalName(signalRequest.getSignalName())
            .build();
    HistoryEvent executionSignaled =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionSignaled)
            .setWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(executionSignaled);
  }

  private void addExecutionSignaledByExternalEvent(
      RequestContext ctx, SignalExternalWorkflowExecutionDecisionAttributes d) {
    WorkflowExecutionSignaledEventAttributes a =
        WorkflowExecutionSignaledEventAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setInput(d.getInput())
            .setSignalName(d.getSignalName())
            .build();
    HistoryEvent executionSignaled =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionSignaled)
            .setWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(executionSignaled);
  }

  private StateMachine<ActivityTaskData> getActivity(String activityId) {
    StateMachine<ActivityTaskData> activity = activities.get(activityId);
    if (activity == null) {
      throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("unknown activityId: " + activityId));
    }
    return activity;
  }

  private StateMachine<ChildWorkflowData> getChildWorkflow(long initiatedEventId) {
    StateMachine<ChildWorkflowData> child = childWorkflows.get(initiatedEventId);
    if (child == null) {
      throw new StatusRuntimeException(
          Status.INTERNAL.withDescription("unknown initiatedEventId: " + initiatedEventId));
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

    byte[] toBytes() {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bout);
      addBytes(out);
      return bout.toByteArray();
    }

    void addBytes(DataOutputStream out) {
      try {
        executionId.addBytes(out);
        out.writeUTF(queryId);
      } catch (IOException e) {
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(e)));
      }
    }

    static QueryId fromBytes(byte[] serialized) {
      ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
      DataInputStream in = new DataInputStream(bin);
      try {
        ExecutionId executionId = ExecutionId.readFromBytes(in);
        String queryId = in.readUTF();
        return new QueryId(executionId, queryId);
      } catch (IOException e) {
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription(Throwables.getStackTraceAsString(e)));
      }
    }
  }
}
