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

package io.temporal.internal.testservice;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.common.StatusUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.StateMachines.Action;
import io.temporal.internal.testservice.StateMachines.ActivityTaskData;
import io.temporal.internal.testservice.StateMachines.CancelExternalData;
import io.temporal.internal.testservice.StateMachines.ChildWorkflowData;
import io.temporal.internal.testservice.StateMachines.DecisionTaskData;
import io.temporal.internal.testservice.StateMachines.SignalExternalData;
import io.temporal.internal.testservice.StateMachines.State;
import io.temporal.internal.testservice.StateMachines.TimerData;
import io.temporal.internal.testservice.StateMachines.WorkflowData;
import io.temporal.internal.testservice.TestWorkflowStore.TaskListId;
import io.temporal.proto.common.RetryPolicy;
import io.temporal.proto.decision.CancelTimerDecisionAttributes;
import io.temporal.proto.decision.CancelWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.CompleteWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.ContinueAsNewWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.Decision;
import io.temporal.proto.decision.FailWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.RecordMarkerDecisionAttributes;
import io.temporal.proto.decision.RequestCancelActivityTaskDecisionAttributes;
import io.temporal.proto.decision.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.ScheduleActivityTaskDecisionAttributes;
import io.temporal.proto.decision.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartTimerDecisionAttributes;
import io.temporal.proto.decision.StickyExecutionAttributes;
import io.temporal.proto.decision.UpsertWorkflowSearchAttributesDecisionAttributes;
import io.temporal.proto.event.ActivityTaskScheduledEventAttributes;
import io.temporal.proto.event.CancelTimerFailedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.event.DecisionTaskFailedCause;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.MarkerRecordedEventAttributes;
import io.temporal.proto.event.RequestCancelActivityTaskFailedEventAttributes;
import io.temporal.proto.event.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.TimeoutType;
import io.temporal.proto.event.UpsertWorkflowSearchAttributesEventAttributes;
import io.temporal.proto.event.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.proto.event.WorkflowExecutionFailedCause;
import io.temporal.proto.event.WorkflowExecutionSignaledEventAttributes;
import io.temporal.proto.execution.WorkflowExecution;
import io.temporal.proto.execution.WorkflowExecutionStatus;
import io.temporal.proto.failure.QueryFailed;
import io.temporal.proto.query.QueryRejectCondition;
import io.temporal.proto.query.QueryRejected;
import io.temporal.proto.query.QueryResultType;
import io.temporal.proto.workflowservice.PollForActivityTaskRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskResponseOrBuilder;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.QueryWorkflowRequest;
import io.temporal.proto.workflowservice.QueryWorkflowResponse;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledByIdRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIdRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedByIdRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondQueryTaskCompletedRequest;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private final Map<String, StateMachine<CancelExternalData>> externalCancellations =
      new HashMap<>();
  private StateMachine<WorkflowData> workflow;
  private volatile StateMachine<DecisionTaskData> decision;
  private long lastNonFailedDecisionStartEventId;
  private final Map<String, CompletableFuture<QueryWorkflowResponse>> queries =
      new ConcurrentHashMap<>();
  private final Map<String, PollForDecisionTaskResponse.Builder> queryRequests =
      new ConcurrentHashMap<>();
  public StickyExecutionAttributes stickyExecutionAttributes;

  /**
   * @param retryState present if workflow is a retry
   * @param backoffStartIntervalInSeconds
   * @param parentChildInitiatedEventId id of the child initiated event in the parent history
   */
  TestWorkflowMutableStateImpl(
      StartWorkflowExecutionRequest startRequest,
      String runId,
      Optional<RetryState> retryState,
      int backoffStartIntervalInSeconds,
      ByteString lastCompletionResult,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<String> continuedExecutionRunId,
      TestWorkflowService service,
      TestWorkflowStore store) {
    this.startRequest = startRequest;
    this.parent = parent;
    this.parentChildInitiatedEventId = parentChildInitiatedEventId;
    this.service = service;
    this.executionId =
        new ExecutionId(startRequest.getNamespace(), startRequest.getWorkflowId(), runId);
    this.store = store;
    selfAdvancingTimer = store.getTimer();
    this.clock = selfAdvancingTimer.getClock();
    WorkflowData data =
        new WorkflowData(
            retryState,
            backoffStartIntervalInSeconds,
            startRequest.getCronSchedule(),
            lastCompletionResult,
            runId, // Test service doesn't support reset. Thus originalRunId is always the same as
            // runId.
            continuedExecutionRunId);
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
      throw e;
    } catch (Exception e) {
      throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
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
  public WorkflowExecutionStatus getWorkflowExecutionStatus() {
    switch (workflow.getState()) {
      case NONE:
      case INITIATED:
      case STARTED:
      case CANCELLATION_REQUESTED:
        return WorkflowExecutionStatus.Running;
      case FAILED:
        return WorkflowExecutionStatus.Failed;
      case TIMED_OUT:
        return WorkflowExecutionStatus.TimedOut;
      case CANCELED:
        return WorkflowExecutionStatus.Canceled;
      case COMPLETED:
        return WorkflowExecutionStatus.Completed;
      case CONTINUED_AS_NEW:
        return WorkflowExecutionStatus.ContinuedAsNew;
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
  public Optional<TestWorkflowMutableState> getParent() {
    return parent;
  }

  @Override
  public void startDecisionTask(
      PollForDecisionTaskResponse.Builder task, PollForDecisionTaskRequest pollRequest) {
    if (!task.hasQuery()) {
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

            throw Status.NOT_FOUND
                .withDescription(
                    "Expired decision: expectedHistorySize="
                        + historySize
                        + ","
                        + " actualHistorySize="
                        + ctx.getInitialEventId())
                .asRuntimeException();
          }
          long decisionTaskCompletedId = ctx.getNextEventId() - 1;
          // Fail the decision if there are new events and the decision tries to complete the
          // workflow
          if (!concurrentToDecision.isEmpty() && hasCompleteDecision(request.getDecisionsList())) {
            RespondDecisionTaskFailedRequest failedRequest =
                RespondDecisionTaskFailedRequest.newBuilder()
                    .setCause(DecisionTaskFailedCause.UnhandledDecision)
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
            throw Status.FAILED_PRECONDITION
                .withDescription("No outstanding decision")
                .asRuntimeException();
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
        request.hasStickyAttributes() ? request.getStickyAttributes() : null);
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
      case CompleteWorkflowExecution:
        processCompleteWorkflowExecution(
            ctx,
            d.getCompleteWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId,
            identity);
        break;
      case FailWorkflowExecution:
        processFailWorkflowExecution(
            ctx, d.getFailWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId, identity);
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
        processRequestCancelExternalWorkflowExecution(
            ctx,
            d.getRequestCancelExternalWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId);
        break;
      case UpsertWorkflowSearchAttributes:
        processUpsertWorkflowSearchAttributes(
            ctx, d.getUpsertWorkflowSearchAttributesDecisionAttributes(), decisionTaskCompletedId);
        break;
    }
  }

  private void processRequestCancelExternalWorkflowExecution(
      RequestContext ctx,
      RequestCancelExternalWorkflowExecutionDecisionAttributes attr,
      long decisionTaskCompletedId) {
    if (externalCancellations.containsKey(attr.getWorkflowId())) {
      // TODO: validate that this matches the service behavior
      throw Status.FAILED_PRECONDITION
          .withDescription("cancellation aready requested for workflowId=" + attr.getWorkflowId())
          .asRuntimeException();
    }
    StateMachine<CancelExternalData> cancelStateMachine =
        StateMachines.newCancelExternalStateMachine();
    externalCancellations.put(attr.getWorkflowId(), cancelStateMachine);
    cancelStateMachine.action(StateMachines.Action.INITIATE, ctx, attr, decisionTaskCompletedId);
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              RequestCancelWorkflowExecutionRequest request =
                  RequestCancelWorkflowExecutionRequest.newBuilder()
                      .setWorkflowExecution(
                          WorkflowExecution.newBuilder().setWorkflowId(attr.getWorkflowId()))
                      .setNamespace(ctx.getNamespace())
                      .build();
              CancelExternalWorkflowExecutionCallerInfo info =
                  new CancelExternalWorkflowExecutionCallerInfo(
                      ctx.getNamespace(),
                      cancelStateMachine.getData().initiatedEventId,
                      executionId.getExecution(),
                      this);
              try {
                service.requestCancelWorkflowExecution(request, Optional.of(info));
              } catch (Exception e) {
                log.error("Failure to request cancel external workflow", e);
              }
            });
  }

  @Override
  public void reportCancelRequested(ExternalWorkflowExecutionCancelRequestedEventAttributes a) {
    update(
        ctx -> {
          StateMachine<CancelExternalData> cancellationRequest =
              externalCancellations.get(a.getWorkflowExecution().getWorkflowId());
          cancellationRequest.action(
              StateMachines.Action.START, ctx, a.getWorkflowExecution().getRunId(), 0);
          scheduleDecision(ctx);
          // No need to lock until completion as child workflow might skip
          // time as well
          //          ctx.unlockTimer();
        });
  }

  private void processRecordMarker(
      RequestContext ctx, RecordMarkerDecisionAttributes attr, long decisionTaskCompletedId) {
    if (attr.getMarkerName().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("marker name is required").asRuntimeException();
    }

    MarkerRecordedEventAttributes.Builder marker =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(attr.getMarkerName())
            .setHeader(attr.getHeader())
            .setDetails(attr.getDetails())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.MarkerRecorded)
            .setMarkerRecordedEventAttributes(marker)
            .build();
    ctx.addEvent(event);
  }

  private void processCancelTimer(
      RequestContext ctx, CancelTimerDecisionAttributes d, long decisionTaskCompletedId) {
    String timerId = d.getTimerId();
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer == null) {
      CancelTimerFailedEventAttributes.Builder failedAttr =
          CancelTimerFailedEventAttributes.newBuilder()
              .setTimerId(timerId)
              .setCause("TIMER_ID_UNKNOWN")
              .setDecisionTaskCompletedEventId(decisionTaskCompletedId);
      HistoryEvent cancellationFailed =
          HistoryEvent.newBuilder()
              .setEventType(EventType.CancelTimerFailed)
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
      RequestCancelActivityTaskFailedEventAttributes.Builder failedAttr =
          RequestCancelActivityTaskFailedEventAttributes.newBuilder()
              .setActivityId(activityId)
              .setCause("ACTIVITY_ID_UNKNOWN")
              .setDecisionTaskCompletedEventId(decisionTaskCompletedId);
      HistoryEvent cancellationFailed =
          HistoryEvent.newBuilder()
              .setEventType(EventType.RequestCancelActivityTaskFailed)
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
    a = validateScheduleActivityTask(a);
    String activityId = a.getActivityId();
    StateMachine<ActivityTaskData> activity = activities.get(activityId);
    if (activity != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already open activity with " + activityId)
          .asRuntimeException();
    }
    activity = StateMachines.newActivityStateMachine(store, this.startRequest);
    activities.put(activityId, activity);
    activity.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ActivityTaskScheduledEventAttributes scheduledEvent = activity.getData().scheduledEvent;
    ctx.addTimer(
        scheduledEvent.getScheduleToCloseTimeoutSeconds(),
        () -> timeoutActivity(activityId, TimeoutType.ScheduleToClose),
        "Activity ScheduleToCloseTimeout");
    ctx.addTimer(
        scheduledEvent.getScheduleToStartTimeoutSeconds(),
        () -> timeoutActivity(activityId, TimeoutType.ScheduleToStart),
        "Activity ScheduleToStartTimeout");
    ctx.lockTimer();
  }

  /**
   * The logic is copied from history service implementation of validateActivityScheduleAttributes
   * function.
   */
  private ScheduleActivityTaskDecisionAttributes validateScheduleActivityTask(
      ScheduleActivityTaskDecisionAttributes a) {
    ScheduleActivityTaskDecisionAttributes.Builder result = a.toBuilder();
    if (!a.hasTaskList() || a.getTaskList().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("TaskList is not set on decision")
          .asRuntimeException();
    }
    if (a.getActivityId().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ActivityId is not set on decision")
          .asRuntimeException();
    }
    if (!a.hasActivityType() || a.getActivityType().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ActivityType is not set on decision")
          .asRuntimeException();
    }
    // Only attempt to deduce and fill in unspecified timeouts only when all timeouts are
    // non-negative.
    if (a.getScheduleToCloseTimeoutSeconds() < 0
        || a.getScheduleToStartTimeoutSeconds() < 0
        || a.getStartToCloseTimeoutSeconds() < 0
        || a.getHeartbeatTimeoutSeconds() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("A valid timeout may not be negative.")
          .asRuntimeException();
    }
    int workflowTimeout = this.startRequest.getExecutionStartToCloseTimeoutSeconds();
    // ensure activity timeout never larger than workflow timeout
    if (a.getScheduleToCloseTimeoutSeconds() > workflowTimeout) {
      result.setScheduleToCloseTimeoutSeconds(workflowTimeout);
    }
    if (a.getScheduleToStartTimeoutSeconds() > workflowTimeout) {
      result.setScheduleToStartTimeoutSeconds(workflowTimeout);
    }
    if (a.getStartToCloseTimeoutSeconds() > workflowTimeout) {
      result.setStartToCloseTimeoutSeconds(workflowTimeout);
    }
    if (a.getHeartbeatTimeoutSeconds() > workflowTimeout) {
      result.setHeartbeatTimeoutSeconds(workflowTimeout);
    }

    boolean validScheduleToClose = a.getScheduleToCloseTimeoutSeconds() > 0;
    boolean validScheduleToStart = a.getScheduleToStartTimeoutSeconds() > 0;
    boolean validStartToClose = a.getStartToCloseTimeoutSeconds() > 0;

    if (validScheduleToClose) {
      if (!validScheduleToStart) {
        result.setScheduleToStartTimeoutSeconds(a.getScheduleToCloseTimeoutSeconds());
      }
      if (!validStartToClose) {
        result.setStartToCloseTimeoutSeconds(a.getScheduleToCloseTimeoutSeconds());
      }
    } else if (validScheduleToStart && validStartToClose) {
      result.setScheduleToCloseTimeoutSeconds(
          a.getScheduleToStartTimeoutSeconds() + a.getStartToCloseTimeoutSeconds());
      if (a.getScheduleToCloseTimeoutSeconds() > workflowTimeout) {
        result.setScheduleToCloseTimeoutSeconds(workflowTimeout);
      }
    } else {
      // Deduction failed as there's not enough information to fill in missing timeouts.
      throw Status.INVALID_ARGUMENT
          .withDescription("A valid ScheduleToCloseTimeout is not set on decision.")
          .asRuntimeException();
    }
    if (a.hasRetryPolicy()) {
      RetryPolicy p = a.getRetryPolicy();
      result.setRetryPolicy(RetryState.validateRetryPolicy(p));
      int expiration = p.getExpirationIntervalInSeconds();
      if (expiration == 0) {
        expiration = workflowTimeout;
      }
      if (a.getScheduleToStartTimeoutSeconds() < expiration) {
        result.setScheduleToStartTimeoutSeconds(expiration);
      }
      if (a.getScheduleToCloseTimeoutSeconds() < expiration) {
        result.setScheduleToCloseTimeoutSeconds(expiration);
      }
    }
    return result.build();
  }

  private void processStartChildWorkflow(
      RequestContext ctx,
      StartChildWorkflowExecutionDecisionAttributes a,
      long decisionTaskCompletedId) {
    a = validateStartChildExecutionAttributes(a);
    StateMachine<ChildWorkflowData> child = StateMachines.newChildWorkflowStateMachine(service);
    childWorkflows.put(ctx.getNextEventId(), child);
    child.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ctx.lockTimer();
  }

  /** Clone of the validateStartChildExecutionAttributes from historyEngine.go */
  private StartChildWorkflowExecutionDecisionAttributes validateStartChildExecutionAttributes(
      StartChildWorkflowExecutionDecisionAttributes a) {
    if (a == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("StartChildWorkflowExecutionDecisionAttributes is not set on decision")
          .asRuntimeException();
    }

    if (a.getWorkflowId().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Required field WorkflowId is not set on decision")
          .asRuntimeException();
    }

    if (!a.hasWorkflowType() || a.getWorkflowType().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Required field WorkflowType is not set on decision")
          .asRuntimeException();
    }

    StartChildWorkflowExecutionDecisionAttributes.Builder ab = a.toBuilder();
    // Inherit tasklist from parent workflow execution if not provided on decision
    if (!ab.hasTaskList()) {
      ab.setTaskList(startRequest.getTaskList());
    }

    // Inherit workflow timeout from parent workflow execution if not provided on decision
    if (a.getExecutionStartToCloseTimeoutSeconds() <= 0) {
      ab.setExecutionStartToCloseTimeoutSeconds(
          startRequest.getExecutionStartToCloseTimeoutSeconds());
    }

    // Inherit decision task timeout from parent workflow execution if not provided on decision
    if (a.getTaskStartToCloseTimeoutSeconds() <= 0) {
      ab.setTaskStartToCloseTimeoutSeconds(startRequest.getTaskStartToCloseTimeoutSeconds());
    }

    if (a.hasRetryPolicy()) {
      ab.setRetryPolicy(RetryState.validateRetryPolicy(a.getRetryPolicy()));
    }
    return ab.build();
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
                service.signalExternalWorkflowExecution(signalId, a, this);
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
      String signalId, WorkflowExecutionFailedCause cause) {
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
      throw Status.FAILED_PRECONDITION
          .withDescription("unknown signalId: " + signalId)
          .asRuntimeException();
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
            decision.action(StateMachines.Action.TIME_OUT, ctx, TimeoutType.StartToClose, 0);
            scheduleDecision(ctx);
          },
          null); // reset sticky attributes to null
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
        // Cannot fail to timer threads
        log.error("Failure trying to timeout a decision scheduledEventId=" + scheduledEventId, e);
      }
      // Expected as timers are not removed
    } catch (Exception e) {
      // Cannot fail to timer threads
      log.error("Failure trying to timeout a decision scheduledEventId=" + scheduledEventId, e);
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
      throw Status.INVALID_ARGUMENT
          .withDescription("A valid TimerId is not set on StartTimerDecision")
          .asRuntimeException();
    }
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already open timer with " + timerId)
          .asRuntimeException();
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
        if (timer == null
            || (workflow.getState() != State.STARTED
                && workflow.getState() != State.CANCELLATION_REQUESTED)) {
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
    } catch (Throwable e) {
      // Cannot fail to timer threads
      log.error("Failure firing a timer", e);
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
        ContinueAsNewWorkflowExecutionDecisionAttributes.Builder continueAsNewAttr =
            ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder()
                .setInput(startRequest.getInput())
                .setWorkflowType(startRequest.getWorkflowType())
                .setExecutionStartToCloseTimeoutSeconds(
                    startRequest.getExecutionStartToCloseTimeoutSeconds())
                .setTaskStartToCloseTimeoutSeconds(startRequest.getTaskStartToCloseTimeoutSeconds())
                .setBackoffStartIntervalInSeconds(backoffIntervalSeconds);
        if (startRequest.hasTaskList()) {
          continueAsNewAttr.setTaskList(startRequest.getTaskList());
        }
        if (startRequest.hasRetryPolicy()) {
          continueAsNewAttr.setRetryPolicy(startRequest.getRetryPolicy());
        }
        if (startRequest.hasHeader()) {
          continueAsNewAttr.setHeader(startRequest.getHeader());
        }
        if (startRequest.hasMemo()) {
          continueAsNewAttr.setMemo(startRequest.getMemo());
        }
        workflow.action(
            Action.CONTINUE_AS_NEW, ctx, continueAsNewAttr.build(), decisionTaskCompletedId);
        HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
        WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewEventAttributes =
            event.getWorkflowExecutionContinuedAsNewEventAttributes();

        Optional<RetryState> continuedRetryState = Optional.of(rs.getNextAttempt());
        String runId =
            service.continueAsNew(
                startRequest,
                continuedAsNewEventAttributes,
                continuedRetryState,
                identity,
                getExecutionId(),
                parent,
                parentChildInitiatedEventId);
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
              .setNamespace(ctx.getNamespace())
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
                  // Parent might already close
                  if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    log.error("Failure reporting child failure", e);
                  }
                } catch (Throwable e) {
                  log.error("Failure reporting child failure", e);
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
              .setNamespace(ctx.getNamespace())
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
                  // Parent might already close
                  if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    log.error("Failure reporting child completion", e);
                  }
                } catch (Throwable e) {
                  log.error("Failure reporting child completion", e);
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
    Cron cron = parseCron(data.cronSchedule);

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
        service.continueAsNew(
            startRequest,
            continuedAsNewEventAttributes,
            Optional.empty(),
            identity,
            getExecutionId(),
            parent,
            parentChildInitiatedEventId);
  }

  static Cron parseCron(String schedule) {
    CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    CronParser parser = new CronParser(cronDefinition);
    return parser.parse(schedule);
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
              .setNamespace(ctx.getNamespace())
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
                  // Parent might already close
                  if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    log.error("Failure reporting child cancellation", e);
                  }
                } catch (Throwable e) {
                  log.error("Failure reporting child cancellation", e);
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
        service.continueAsNew(
            startRequest,
            event.getWorkflowExecutionContinuedAsNewEventAttributes(),
            workflow.getData().retryState,
            identity,
            getExecutionId(),
            parent,
            parentChildInitiatedEventId);
  }

  private void processUpsertWorkflowSearchAttributes(
      RequestContext ctx,
      UpsertWorkflowSearchAttributesDecisionAttributes attr,
      long decisionTaskCompletedId) {
    UpsertWorkflowSearchAttributesEventAttributes.Builder upsertEventAttr =
        UpsertWorkflowSearchAttributesEventAttributes.newBuilder()
            .setSearchAttributes(attr.getSearchAttributes())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.UpsertWorkflowSearchAttributes)
            .setUpsertWorkflowSearchAttributesEventAttributes(upsertEventAttr)
            .build();
    ctx.addEvent(event);
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
                      // NOT_FOUND is expected as timers are not removed
                      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                        log.error("Failure trying to add task for an delayed workflow retry", e);
                      }
                    } catch (Throwable e) {
                      log.error("Failure trying to add task for an delayed workflow retry", e);
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
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
      }
      throw e;
    }
    if (!continuedAsNew && parent.isPresent()) {
      ChildWorkflowExecutionStartedEventAttributes a =
          ChildWorkflowExecutionStartedEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setWorkflowExecution(getExecutionId().getExecution())
              .setNamespace(getExecutionId().getNamespace())
              .setWorkflowType(startRequest.getWorkflowType())
              .build();
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  parent.get().childWorkflowStarted(a);
                } catch (StatusRuntimeException e) {
                  // NOT_FOUND is expected as the parent might just close by now.
                  if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    log.error("Failure reporting child completion", e);
                  }
                } catch (Throwable e) {
                  log.error("Failure trying to add task for an delayed workflow retry", e);
                }
              });
    }
  }

  private void scheduleDecision(RequestContext ctx) {
    log.trace("scheduleDecision begin");
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
      throw Status.INTERNAL
          .withDescription("unexpected decision state: " + decision.getState())
          .asRuntimeException();
    }
    this.decision = StateMachines.newDecisionStateMachine(lastNonFailedDecisionStartEventId, store);
    decision.action(StateMachines.Action.INITIATE, ctx, startRequest, 0);
    ctx.lockTimer();
  }

  @Override
  public void startActivityTask(
      PollForActivityTaskResponseOrBuilder task, PollForActivityTaskRequest pollRequest) {
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
                () -> timeoutActivity(activityId, TimeoutType.StartToClose),
                "Activity StartToCloseTimeout");
          }
          updateHeartbeatTimer(ctx, activityId, activity, startToCloseTimeout, heartbeatTimeout);
        });
  }

  @Override
  public boolean isTerminalState() {
    State workflowState = workflow.getState();
    return isTerminalState(workflowState);
  }

  private void checkCompleted() {
    State workflowState = workflow.getState();
    if (isTerminalState(workflowState)) {
      throw Status.NOT_FOUND
          .withDescription("Workflow is already completed: " + workflowState)
          .asRuntimeException();
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
          () -> timeoutActivity(activityId, TimeoutType.Heartbeat),
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
      String activityId, RespondActivityTaskCompletedByIdRequest request) {
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
            // NOT_FOUND is expected as timers are not removed
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
              log.error("Failure trying to add task for an activity retry", e);
            }
            unlockTimer = true;
          } catch (Exception e) {
            unlockTimer = true;
            // Cannot fail to timer threads
            log.error("Failure trying to add task for an activity retry", e);
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
      String activityId, RespondActivityTaskFailedByIdRequest request) {
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
      String activityId, RespondActivityTaskCanceledByIdRequest request) {
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
  public boolean heartbeatActivityTask(String activityId, ByteString details) {
    AtomicBoolean result = new AtomicBoolean();
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivity(activityId);
          activity.action(StateMachines.Action.UPDATE, ctx, details, 0);
          if (activity.getState() == StateMachines.State.CANCELLATION_REQUESTED) {
            result.set(true);
          }
          ActivityTaskData data = activity.getData();
          data.lastHeartbeatTime = clock.getAsLong();
          int startToCloseTimeout = data.scheduledEvent.getStartToCloseTimeoutSeconds();
          int heartbeatTimeout = data.scheduledEvent.getHeartbeatTimeoutSeconds();
          updateHeartbeatTimer(ctx, activityId, activity, startToCloseTimeout, heartbeatTimeout);
        });
    return result.get();
  }

  private void timeoutActivity(String activityId, TimeoutType timeoutType) {
    boolean unlockTimer = true;
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity = getActivity(activityId);
            if (timeoutType == TimeoutType.ScheduleToStart
                && activity.getState() != StateMachines.State.INITIATED) {
              throw Status.INTERNAL.withDescription("Not in INITIATED").asRuntimeException();
            }
            if (timeoutType == TimeoutType.Heartbeat) {
              // Deal with timers which are never cancelled
              long heartbeatTimeout =
                  TimeUnit.SECONDS.toMillis(
                      activity.getData().scheduledEvent.getHeartbeatTimeoutSeconds());
              if (clock.getAsLong() - activity.getData().lastHeartbeatTime < heartbeatTimeout) {
                throw Status.INTERNAL.withDescription("Not heartbeat timeout").asRuntimeException();
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
      // NOT_FOUND is expected as timers are not removed
      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
        log.error("Failure trying to add task for an activity retry", e);
      }
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
            workflow.action(StateMachines.Action.TIME_OUT, ctx, TimeoutType.StartToClose, 0);
            if (parent != null) {
              ctx.lockTimer(); // unlocked by the parent
            }
            ForkJoinPool.commonPool().execute(() -> reportWorkflowTimeoutToParent(ctx));
          });
    } catch (Exception e) {
      // Cannot fail to timer threads
      log.error("Failure trying to timeout a workflow", e);
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
              .setTimeoutType(TimeoutType.StartToClose)
              .setWorkflowType(startRequest.getWorkflowType())
              .setNamespace(ctx.getNamespace())
              .setWorkflowExecution(ctx.getExecution())
              .build();
      parent.get().childWorkflowTimedOut(ctx.getExecutionId().getWorkflowId().getWorkflowId(), a);
    } catch (StatusRuntimeException e) {
      // NOT_FOUND is expected as parent might already close
      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
        log.error("Failure reporting child timing out", e);
      }
    } catch (Exception e) {
      log.error("Failure reporting child timing out", e);
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

  static class CancelExternalWorkflowExecutionCallerInfo {
    private final String namespace;
    private final long externalInitiatedEventId;
    private final TestWorkflowMutableState caller;

    CancelExternalWorkflowExecutionCallerInfo(
        String namespace,
        long externalInitiatedEventId,
        WorkflowExecution workflowExecution,
        TestWorkflowMutableState caller) {
      this.namespace = namespace;
      this.externalInitiatedEventId = externalInitiatedEventId;
      this.caller = caller;
    }

    public String getNamespace() {
      return namespace;
    }

    public long getExternalInitiatedEventId() {
      return externalInitiatedEventId;
    }

    public TestWorkflowMutableState getCaller() {
      return caller;
    }
  }

  @Override
  public void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      Optional<CancelExternalWorkflowExecutionCallerInfo> callerInfo) {
    update(
        ctx -> {
          workflow.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, cancelRequest, 0);
          scheduleDecision(ctx);
        });
    if (callerInfo.isPresent()) {
      CancelExternalWorkflowExecutionCallerInfo ci = callerInfo.get();
      ExternalWorkflowExecutionCancelRequestedEventAttributes a =
          ExternalWorkflowExecutionCancelRequestedEventAttributes.newBuilder()
              .setInitiatedEventId(ci.getExternalInitiatedEventId())
              .setWorkflowExecution(executionId.getExecution())
              .setNamespace(ci.getNamespace())
              .build();
      ForkJoinPool.commonPool()
          .execute(
              () -> {
                try {
                  ci.getCaller().reportCancelRequested(a);
                } catch (StatusRuntimeException e) {
                  // NOT_FOUND is expected as the parent might just close by now.
                  if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    log.error("Failure reporting external cancellation requested", e);
                  }
                } catch (Throwable e) {
                  log.error("Failure reporting external cancellation requested", e);
                }
              });
    }
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryRequest) {
    QueryId queryId = new QueryId(executionId);

    WorkflowExecutionStatus status = getWorkflowExecutionStatus();
    if (status != WorkflowExecutionStatus.Running
        && queryRequest.getQueryRejectCondition() != null) {
      boolean rejectNotOpen =
          queryRequest.getQueryRejectCondition() == QueryRejectCondition.NotOpen;
      boolean rejectNotCompletedCleanly =
          queryRequest.getQueryRejectCondition() == QueryRejectCondition.NotCompletedCleanly
              && status != WorkflowExecutionStatus.Completed;
      if (rejectNotOpen || rejectNotCompletedCleanly) {
        return QueryWorkflowResponse.newBuilder()
            .setQueryRejected(QueryRejected.newBuilder().setStatus(status))
            .build();
      }
    }

    PollForDecisionTaskResponse.Builder task =
        PollForDecisionTaskResponse.newBuilder()
            .setTaskToken(queryId.toBytes())
            .setWorkflowExecution(executionId.getExecution())
            .setWorkflowType(startRequest.getWorkflowType())
            .setQuery(queryRequest.getQuery())
            .setWorkflowExecutionTaskList(startRequest.getTaskList());
    TaskListId taskListId =
        new TaskListId(
            queryRequest.getNamespace(),
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
      throw Status.INTERNAL
          .withCause(cause)
          .withDescription(cause.getMessage())
          .asRuntimeException();
    }
  }

  @Override
  public void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest) {
    CompletableFuture<QueryWorkflowResponse> result = queries.get(queryId.getQueryId());
    if (result == null) {
      throw Status.NOT_FOUND
          .withDescription("Unknown query id: " + queryId.getQueryId())
          .asRuntimeException();
    }
    if (completeRequest.getCompletedType() == QueryResultType.Answered) {
      QueryWorkflowResponse response =
          QueryWorkflowResponse.newBuilder()
              .setQueryResult(completeRequest.getQueryResult())
              .build();
      result.complete(response);
    } else if (completeRequest.getCompletedType() == QueryResultType.Failed) {
      StatusRuntimeException error =
          StatusUtils.newException(
              Status.INVALID_ARGUMENT.withDescription(completeRequest.getErrorMessage()),
              QueryFailed.getDefaultInstance());
      result.completeExceptionally(error);
    } else if (stickyExecutionAttributes != null) {
      // TODO(maxim): This should happen on timeout only. I believe this branch is not reachable.
      stickyExecutionAttributes = null;
      PollForDecisionTaskResponse.Builder task = queryRequests.remove(queryId.getQueryId());
      TaskListId taskListId =
          new TaskListId(startRequest.getNamespace(), startRequest.getTaskList().getName());
      store.sendQueryTask(executionId, taskListId, task);
    }
  }

  private void addExecutionSignaledEvent(
      RequestContext ctx, SignalWorkflowExecutionRequest signalRequest) {
    WorkflowExecutionSignaledEventAttributes.Builder a =
        WorkflowExecutionSignaledEventAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setIdentity(signalRequest.getIdentity())
            .setInput(signalRequest.getInput())
            .setSignalName(signalRequest.getSignalName());
    HistoryEvent executionSignaled =
        HistoryEvent.newBuilder()
            .setEventType(EventType.WorkflowExecutionSignaled)
            .setWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(executionSignaled);
  }

  private void addExecutionSignaledByExternalEvent(
      RequestContext ctx, SignalExternalWorkflowExecutionDecisionAttributes d) {
    WorkflowExecutionSignaledEventAttributes.Builder a =
        WorkflowExecutionSignaledEventAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setInput(d.getInput())
            .setSignalName(d.getSignalName());
    HistoryEvent executionSignaled =
        HistoryEvent.newBuilder()
            .setEventType(EventType.WorkflowExecutionSignaled)
            .setWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(executionSignaled);
  }

  private StateMachine<ActivityTaskData> getActivity(String activityId) {
    StateMachine<ActivityTaskData> activity = activities.get(activityId);
    if (activity == null) {
      throw Status.NOT_FOUND
          .withDescription("unknown activityId: " + activityId)
          .asRuntimeException();
    }
    return activity;
  }

  private StateMachine<ChildWorkflowData> getChildWorkflow(long initiatedEventId) {
    StateMachine<ChildWorkflowData> child = childWorkflows.get(initiatedEventId);
    if (child == null) {
      throw Status.INTERNAL
          .withDescription("unknown initiatedEventId: " + initiatedEventId)
          .asRuntimeException();
    }
    return child;
  }
}
