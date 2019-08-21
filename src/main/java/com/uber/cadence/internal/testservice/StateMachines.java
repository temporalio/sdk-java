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

import static com.uber.cadence.internal.testservice.StateMachines.Action.CANCEL;
import static com.uber.cadence.internal.testservice.StateMachines.Action.COMPLETE;
import static com.uber.cadence.internal.testservice.StateMachines.Action.CONTINUE_AS_NEW;
import static com.uber.cadence.internal.testservice.StateMachines.Action.FAIL;
import static com.uber.cadence.internal.testservice.StateMachines.Action.INITIATE;
import static com.uber.cadence.internal.testservice.StateMachines.Action.REQUEST_CANCELLATION;
import static com.uber.cadence.internal.testservice.StateMachines.Action.START;
import static com.uber.cadence.internal.testservice.StateMachines.Action.TIME_OUT;
import static com.uber.cadence.internal.testservice.StateMachines.Action.UPDATE;
import static com.uber.cadence.internal.testservice.StateMachines.State.CANCELED;
import static com.uber.cadence.internal.testservice.StateMachines.State.CANCELLATION_REQUESTED;
import static com.uber.cadence.internal.testservice.StateMachines.State.COMPLETED;
import static com.uber.cadence.internal.testservice.StateMachines.State.CONTINUED_AS_NEW;
import static com.uber.cadence.internal.testservice.StateMachines.State.FAILED;
import static com.uber.cadence.internal.testservice.StateMachines.State.INITIATED;
import static com.uber.cadence.internal.testservice.StateMachines.State.NONE;
import static com.uber.cadence.internal.testservice.StateMachines.State.STARTED;
import static com.uber.cadence.internal.testservice.StateMachines.State.TIMED_OUT;

import com.uber.cadence.ActivityTaskCancelRequestedEventAttributes;
import com.uber.cadence.ActivityTaskCanceledEventAttributes;
import com.uber.cadence.ActivityTaskCompletedEventAttributes;
import com.uber.cadence.ActivityTaskFailedEventAttributes;
import com.uber.cadence.ActivityTaskScheduledEventAttributes;
import com.uber.cadence.ActivityTaskStartedEventAttributes;
import com.uber.cadence.ActivityTaskTimedOutEventAttributes;
import com.uber.cadence.BadRequestError;
import com.uber.cadence.CancelTimerDecisionAttributes;
import com.uber.cadence.CancelWorkflowExecutionDecisionAttributes;
import com.uber.cadence.ChildWorkflowExecutionCanceledEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionFailedCause;
import com.uber.cadence.ChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionStartedEventAttributes;
import com.uber.cadence.ChildWorkflowExecutionTimedOutEventAttributes;
import com.uber.cadence.CompleteWorkflowExecutionDecisionAttributes;
import com.uber.cadence.ContinueAsNewWorkflowExecutionDecisionAttributes;
import com.uber.cadence.DecisionTaskCompletedEventAttributes;
import com.uber.cadence.DecisionTaskFailedEventAttributes;
import com.uber.cadence.DecisionTaskScheduledEventAttributes;
import com.uber.cadence.DecisionTaskStartedEventAttributes;
import com.uber.cadence.DecisionTaskTimedOutEventAttributes;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.EventType;
import com.uber.cadence.ExternalWorkflowExecutionSignaledEventAttributes;
import com.uber.cadence.FailWorkflowExecutionDecisionAttributes;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.RequestCancelActivityTaskDecisionAttributes;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.RespondActivityTaskCanceledByIDRequest;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedByIDRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedByIDRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RetryPolicy;
import com.uber.cadence.ScheduleActivityTaskDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionInitiatedEventAttributes;
import com.uber.cadence.StartChildWorkflowExecutionDecisionAttributes;
import com.uber.cadence.StartChildWorkflowExecutionFailedEventAttributes;
import com.uber.cadence.StartChildWorkflowExecutionInitiatedEventAttributes;
import com.uber.cadence.StartTimerDecisionAttributes;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.TimerCanceledEventAttributes;
import com.uber.cadence.TimerFiredEventAttributes;
import com.uber.cadence.TimerStartedEventAttributes;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowExecutionCancelRequestedEventAttributes;
import com.uber.cadence.WorkflowExecutionCanceledEventAttributes;
import com.uber.cadence.WorkflowExecutionCompletedEventAttributes;
import com.uber.cadence.WorkflowExecutionContinuedAsNewEventAttributes;
import com.uber.cadence.WorkflowExecutionFailedEventAttributes;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowExecutionTimedOutEventAttributes;
import com.uber.cadence.internal.testservice.TestWorkflowStore.ActivityTask;
import com.uber.cadence.internal.testservice.TestWorkflowStore.DecisionTask;
import com.uber.cadence.internal.testservice.TestWorkflowStore.TaskListId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StateMachines {

  private static final Logger log = LoggerFactory.getLogger(StateMachines.class);

  private static final int NO_EVENT_ID = -1;
  private static final String TIMEOUT_ERROR_REASON = "cadenceInternal:Timeout";

  enum State {
    NONE,
    INITIATED,
    STARTED,
    FAILED,
    TIMED_OUT,
    CANCELLATION_REQUESTED,
    CANCELED,
    COMPLETED,
    CONTINUED_AS_NEW,
  }

  enum Action {
    INITIATE,
    START,
    FAIL,
    TIME_OUT,
    REQUEST_CANCELLATION,
    CANCEL,
    UPDATE,
    COMPLETE,
    CONTINUE_AS_NEW
  }

  static final class WorkflowData {
    Optional<RetryState> retryState = Optional.empty();
    int backoffStartIntervalInSeconds;
    String cronSchedule;
    byte[] lastCompletionResult;

    WorkflowData(
        Optional<RetryState> retryState,
        int backoffStartIntervalInSeconds,
        String cronSchedule,
        byte[] lastCompletionResult) {
      this.retryState = retryState;
      this.backoffStartIntervalInSeconds = backoffStartIntervalInSeconds;
      this.cronSchedule = cronSchedule;
      this.lastCompletionResult = lastCompletionResult;
    }
  }

  static final class DecisionTaskData {

    final long previousStartedEventId;

    final TestWorkflowStore store;

    long startedEventId = NO_EVENT_ID;

    PollForDecisionTaskResponse decisionTask;

    long scheduledEventId = NO_EVENT_ID;

    int attempt;

    DecisionTaskData(long previousStartedEventId, TestWorkflowStore store) {
      this.previousStartedEventId = previousStartedEventId;
      this.store = store;
    }
  }

  static final class ActivityTaskData {

    StartWorkflowExecutionRequest startWorkflowExecutionRequest;
    ActivityTaskScheduledEventAttributes scheduledEvent;
    ActivityTask activityTask;

    final TestWorkflowStore store;

    long scheduledEventId = NO_EVENT_ID;
    long startedEventId = NO_EVENT_ID;
    public HistoryEvent startedEvent;
    byte[] heartbeatDetails;
    long lastHeartbeatTime;
    RetryState retryState;
    long nextBackoffIntervalSeconds;

    ActivityTaskData(
        TestWorkflowStore store, StartWorkflowExecutionRequest startWorkflowExecutionRequest) {
      this.store = store;
      this.startWorkflowExecutionRequest = startWorkflowExecutionRequest;
    }
  }

  static final class SignalExternalData {

    long initiatedEventId = NO_EVENT_ID;
    public SignalExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent;
  }

  static final class ChildWorkflowData {

    final TestWorkflowService service;
    StartChildWorkflowExecutionInitiatedEventAttributes initiatedEvent;
    long initiatedEventId;
    long startedEventId;
    WorkflowExecution execution;

    public ChildWorkflowData(TestWorkflowService service) {
      this.service = service;
    }
  }

  static final class TimerData {

    TimerStartedEventAttributes startedEvent;
    public long startedEventId;
  }

  static StateMachine<WorkflowData> newWorkflowStateMachine(WorkflowData data) {
    return new StateMachine<>(data)
        .add(NONE, START, STARTED, StateMachines::startWorkflow)
        .add(STARTED, COMPLETE, COMPLETED, StateMachines::completeWorkflow)
        .add(STARTED, CONTINUE_AS_NEW, CONTINUED_AS_NEW, StateMachines::continueAsNewWorkflow)
        .add(STARTED, FAIL, FAILED, StateMachines::failWorkflow)
        .add(STARTED, TIME_OUT, TIMED_OUT, StateMachines::timeoutWorkflow)
        .add(
            STARTED,
            REQUEST_CANCELLATION,
            CANCELLATION_REQUESTED,
            StateMachines::requestWorkflowCancellation)
        .add(CANCELLATION_REQUESTED, COMPLETE, COMPLETED, StateMachines::completeWorkflow)
        .add(CANCELLATION_REQUESTED, CANCEL, CANCELED, StateMachines::cancelWorkflow)
        .add(CANCELLATION_REQUESTED, FAIL, FAILED, StateMachines::failWorkflow)
        .add(CANCELLATION_REQUESTED, TIME_OUT, TIMED_OUT, StateMachines::timeoutWorkflow);
  }

  static StateMachine<DecisionTaskData> newDecisionStateMachine(
      long previousStartedEventId, TestWorkflowStore store) {
    return new StateMachine<>(new DecisionTaskData(previousStartedEventId, store))
        .add(NONE, INITIATE, INITIATED, StateMachines::scheduleDecisionTask)
        .add(INITIATED, START, STARTED, StateMachines::startDecisionTask)
        .add(STARTED, COMPLETE, COMPLETED, StateMachines::completeDecisionTask)
        .add(STARTED, FAIL, FAILED, StateMachines::failDecisionTask)
        .add(STARTED, TIME_OUT, TIMED_OUT, StateMachines::timeoutDecisionTask)
        .add(TIMED_OUT, INITIATE, INITIATED, StateMachines::scheduleDecisionTask)
        .add(FAILED, INITIATE, INITIATED, StateMachines::scheduleDecisionTask);
  }

  public static StateMachine<ActivityTaskData> newActivityStateMachine(
      TestWorkflowStore store, StartWorkflowExecutionRequest workflowStartedEvent) {
    return new StateMachine<>(new ActivityTaskData(store, workflowStartedEvent))
        .add(NONE, INITIATE, INITIATED, StateMachines::scheduleActivityTask)
        .add(INITIATED, START, STARTED, StateMachines::startActivityTask)
        .add(INITIATED, TIME_OUT, TIMED_OUT, StateMachines::timeoutActivityTask)
        .add(
            INITIATED,
            REQUEST_CANCELLATION,
            CANCELLATION_REQUESTED,
            StateMachines::requestActivityCancellation)
        .add(STARTED, COMPLETE, COMPLETED, StateMachines::completeActivityTask)
        // Transitions to initiated in case of the a retry
        .add(STARTED, FAIL, new State[] {FAILED, INITIATED}, StateMachines::failActivityTask)
        // Transitions to initiated in case of a retry
        .add(
            STARTED,
            TIME_OUT,
            new State[] {TIMED_OUT, INITIATED},
            StateMachines::timeoutActivityTask)
        .add(STARTED, UPDATE, STARTED, StateMachines::heartbeatActivityTask)
        .add(
            STARTED,
            REQUEST_CANCELLATION,
            CANCELLATION_REQUESTED,
            StateMachines::requestActivityCancellation)
        .add(
            CANCELLATION_REQUESTED, CANCEL, CANCELED, StateMachines::reportActivityTaskCancellation)
        .add(CANCELLATION_REQUESTED, COMPLETE, COMPLETED, StateMachines::completeActivityTask)
        .add(
            CANCELLATION_REQUESTED,
            UPDATE,
            CANCELLATION_REQUESTED,
            StateMachines::heartbeatActivityTask)
        .add(CANCELLATION_REQUESTED, TIME_OUT, TIMED_OUT, StateMachines::timeoutActivityTask)
        .add(CANCELLATION_REQUESTED, FAIL, FAILED, StateMachines::failActivityTask);
  }

  public static StateMachine<ChildWorkflowData> newChildWorkflowStateMachine(
      TestWorkflowService service) {
    return new StateMachine<>(new ChildWorkflowData(service))
        .add(NONE, INITIATE, INITIATED, StateMachines::initiateChildWorkflow)
        .add(INITIATED, START, STARTED, StateMachines::childWorkflowStarted)
        .add(INITIATED, FAIL, FAILED, StateMachines::startChildWorkflowFailed)
        .add(INITIATED, TIME_OUT, TIMED_OUT, StateMachines::timeoutChildWorkflow)
        .add(STARTED, COMPLETE, COMPLETED, StateMachines::childWorkflowCompleted)
        .add(STARTED, FAIL, FAILED, StateMachines::childWorkflowFailed)
        .add(STARTED, TIME_OUT, TIMED_OUT, StateMachines::timeoutChildWorkflow)
        .add(STARTED, CANCEL, CANCELED, StateMachines::childWorkflowCanceled);
  }

  public static StateMachine<TimerData> newTimerStateMachine() {
    return new StateMachine<>(new TimerData())
        .add(NONE, START, STARTED, StateMachines::startTimer)
        .add(STARTED, COMPLETE, COMPLETED, StateMachines::fireTimer)
        .add(STARTED, CANCEL, CANCELED, StateMachines::cancelTimer);
  }

  public static StateMachine<SignalExternalData> newSignalExternalStateMachine() {
    return new StateMachine<>(new SignalExternalData())
        .add(NONE, INITIATE, INITIATED, StateMachines::initiateExternalSignal)
        .add(INITIATED, FAIL, FAILED, StateMachines::failExternalSignal)
        .add(INITIATED, COMPLETE, COMPLETED, StateMachines::completeExternalSignal);
  }

  private static void timeoutChildWorkflow(
      RequestContext ctx, ChildWorkflowData data, TimeoutType timeoutType, long notUsed) {
    StartChildWorkflowExecutionInitiatedEventAttributes ie = data.initiatedEvent;
    ChildWorkflowExecutionTimedOutEventAttributes a =
        new ChildWorkflowExecutionTimedOutEventAttributes()
            .setDomain(ie.getDomain())
            .setStartedEventId(data.startedEventId)
            .setWorkflowExecution(data.execution)
            .setWorkflowType(ie.getWorkflowType())
            .setTimeoutType(timeoutType)
            .setInitiatedEventId(data.initiatedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ChildWorkflowExecutionTimedOut)
            .setChildWorkflowExecutionTimedOutEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void startChildWorkflowFailed(
      RequestContext ctx,
      ChildWorkflowData data,
      StartChildWorkflowExecutionFailedEventAttributes a,
      long notUsed) {
    a.setInitiatedEventId(data.initiatedEventId);
    a.setWorkflowType(data.initiatedEvent.getWorkflowType());
    a.setWorkflowId(data.initiatedEvent.getWorkflowId());
    if (data.initiatedEvent.isSetDomain()) {
      a.setDomain(data.initiatedEvent.getDomain());
    }
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.StartChildWorkflowExecutionFailed)
            .setStartChildWorkflowExecutionFailedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void childWorkflowStarted(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionStartedEventAttributes a,
      long notUsed) {
    a.setInitiatedEventId(data.initiatedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ChildWorkflowExecutionStarted)
            .setChildWorkflowExecutionStartedEventAttributes(a);
    long startedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.startedEventId = startedEventId;
          data.execution = a.getWorkflowExecution();
        });
  }

  private static void childWorkflowCompleted(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionCompletedEventAttributes a,
      long notUsed) {
    a.setInitiatedEventId(data.initiatedEventId).setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ChildWorkflowExecutionCompleted)
            .setChildWorkflowExecutionCompletedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void childWorkflowFailed(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionFailedEventAttributes a,
      long notUsed) {
    a.setInitiatedEventId(data.initiatedEventId);
    a.setStartedEventId(data.startedEventId);
    a.setWorkflowExecution(data.execution);
    a.setWorkflowType(data.initiatedEvent.getWorkflowType());
    if (data.initiatedEvent.domain != null) {
      a.setDomain(data.initiatedEvent.domain);
    }
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ChildWorkflowExecutionFailed)
            .setChildWorkflowExecutionFailedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void childWorkflowCanceled(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionCanceledEventAttributes a,
      long notUsed) {
    a.setInitiatedEventId(data.initiatedEventId);
    a.setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ChildWorkflowExecutionCanceled)
            .setChildWorkflowExecutionCanceledEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void initiateChildWorkflow(
      RequestContext ctx,
      ChildWorkflowData data,
      StartChildWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    StartChildWorkflowExecutionInitiatedEventAttributes a =
        new StartChildWorkflowExecutionInitiatedEventAttributes()
            .setControl(d.getControl())
            .setInput(d.getInput())
            .setChildPolicy(d.getChildPolicy())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setDomain(d.getDomain() == null ? ctx.getDomain() : d.getDomain())
            .setExecutionStartToCloseTimeoutSeconds(d.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(d.getTaskStartToCloseTimeoutSeconds())
            .setTaskList(d.getTaskList())
            .setWorkflowId(d.getWorkflowId())
            .setWorkflowIdReusePolicy(d.getWorkflowIdReusePolicy())
            .setWorkflowType(d.getWorkflowType())
            .setRetryPolicy(d.getRetryPolicy())
            .setCronSchedule(d.getCronSchedule());
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.StartChildWorkflowExecutionInitiated)
            .setStartChildWorkflowExecutionInitiatedEventAttributes(a);
    long initiatedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.initiatedEventId = initiatedEventId;
          data.initiatedEvent = a;
          StartWorkflowExecutionRequest startChild =
              new StartWorkflowExecutionRequest()
                  .setDomain(d.getDomain() == null ? ctx.getDomain() : d.getDomain())
                  .setExecutionStartToCloseTimeoutSeconds(
                      d.getExecutionStartToCloseTimeoutSeconds())
                  .setTaskStartToCloseTimeoutSeconds(d.getTaskStartToCloseTimeoutSeconds())
                  .setTaskList(d.getTaskList())
                  .setWorkflowId(d.getWorkflowId())
                  .setWorkflowIdReusePolicy(d.getWorkflowIdReusePolicy())
                  .setWorkflowType(d.getWorkflowType())
                  .setRetryPolicy(d.getRetryPolicy())
                  .setCronSchedule(d.getCronSchedule());
          if (d.isSetInput()) {
            startChild.setInput(d.getInput());
          }
          addStartChildTask(ctx, data, initiatedEventId, startChild);
        });
  }

  private static void addStartChildTask(
      RequestContext ctx,
      ChildWorkflowData data,
      long initiatedEventId,
      StartWorkflowExecutionRequest startChild) {
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              try {
                data.service.startWorkflowExecutionImpl(
                    startChild,
                    0,
                    Optional.of(ctx.getWorkflowMutableState()),
                    OptionalLong.of(data.initiatedEventId),
                    Optional.empty());
              } catch (WorkflowExecutionAlreadyStartedError workflowExecutionAlreadyStartedError) {
                StartChildWorkflowExecutionFailedEventAttributes failRequest =
                    new StartChildWorkflowExecutionFailedEventAttributes()
                        .setInitiatedEventId(initiatedEventId)
                        .setCause(ChildWorkflowExecutionFailedCause.WORKFLOW_ALREADY_RUNNING);
                try {
                  ctx.getWorkflowMutableState()
                      .failStartChildWorkflow(data.initiatedEvent.getWorkflowId(), failRequest);
                } catch (Throwable e) {
                  log.error("Unexpected failure inserting failStart for a child workflow", e);
                }
              } catch (Exception e) {
                log.error("Unexpected failure starting a child workflow", e);
              }
            });
  }

  private static void startWorkflow(
      RequestContext ctx, WorkflowData data, StartWorkflowExecutionRequest request, long notUsed)
      throws BadRequestError {
    WorkflowExecutionStartedEventAttributes a = new WorkflowExecutionStartedEventAttributes();
    if (request.isSetIdentity()) {
      a.setIdentity(request.getIdentity());
    }
    if (!request.isSetTaskStartToCloseTimeoutSeconds()) {
      throw new BadRequestError("missing taskStartToCloseTimeoutSeconds");
    }
    a.setTaskStartToCloseTimeoutSeconds(request.getTaskStartToCloseTimeoutSeconds());
    if (!request.isSetWorkflowType()) {
      throw new BadRequestError("missing workflowType");
    }
    a.setWorkflowType(request.getWorkflowType());
    if (!request.isSetTaskList()) {
      throw new BadRequestError("missing taskList");
    }
    a.setTaskList(request.getTaskList());
    if (!request.isSetExecutionStartToCloseTimeoutSeconds()) {
      throw new BadRequestError("missing executionStartToCloseTimeoutSeconds");
    }
    a.setExecutionStartToCloseTimeoutSeconds(request.getExecutionStartToCloseTimeoutSeconds());
    if (request.isSetInput()) {
      a.setInput(request.getInput());
    }
    if (data.retryState.isPresent()) {
      a.setAttempt(data.retryState.get().getAttempt());
    }
    a.setLastCompletionResult(data.lastCompletionResult);
    a.setMemo(request.getMemo());
    a.setSearchAttributes((request.getSearchAttributes()));
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionStarted)
            .setWorkflowExecutionStartedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void completeWorkflow(
      RequestContext ctx,
      WorkflowData data,
      CompleteWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    WorkflowExecutionCompletedEventAttributes a =
        new WorkflowExecutionCompletedEventAttributes()
            .setResult(d.getResult())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionCompleted)
            .setWorkflowExecutionCompletedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void continueAsNewWorkflow(
      RequestContext ctx,
      WorkflowData data,
      ContinueAsNewWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    StartWorkflowExecutionRequest sr = ctx.getWorkflowMutableState().getStartRequest();
    WorkflowExecutionContinuedAsNewEventAttributes a =
        new WorkflowExecutionContinuedAsNewEventAttributes();
    a.setInput(d.getInput());
    if (d.isSetExecutionStartToCloseTimeoutSeconds()) {
      a.setExecutionStartToCloseTimeoutSeconds(d.getExecutionStartToCloseTimeoutSeconds());
    } else {
      a.setExecutionStartToCloseTimeoutSeconds(sr.getExecutionStartToCloseTimeoutSeconds());
    }
    if (d.isSetTaskList()) {
      a.setTaskList(d.getTaskList());
    } else {
      a.setTaskList(sr.getTaskList());
    }
    if (d.isSetWorkflowType()) {
      a.setWorkflowType(d.getWorkflowType());
    } else {
      a.setWorkflowType(sr.getWorkflowType());
    }
    if (d.isSetTaskStartToCloseTimeoutSeconds()) {
      a.setTaskStartToCloseTimeoutSeconds(d.getTaskStartToCloseTimeoutSeconds());
    } else {
      a.setTaskStartToCloseTimeoutSeconds(sr.getTaskStartToCloseTimeoutSeconds());
    }
    a.setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    a.setBackoffStartIntervalInSeconds(d.getBackoffStartIntervalInSeconds());
    a.setLastCompletionResult(d.getLastCompletionResult());
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionContinuedAsNew)
            .setWorkflowExecutionContinuedAsNewEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void failWorkflow(
      RequestContext ctx,
      WorkflowData data,
      FailWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    WorkflowExecutionFailedEventAttributes a =
        new WorkflowExecutionFailedEventAttributes()
            .setReason(d.getReason())
            .setDetails(d.getDetails())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionFailed)
            .setWorkflowExecutionFailedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void timeoutWorkflow(
      RequestContext ctx, WorkflowData data, TimeoutType timeoutType, long notUsed) {
    WorkflowExecutionTimedOutEventAttributes a =
        new WorkflowExecutionTimedOutEventAttributes().setTimeoutType(timeoutType);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionTimedOut)
            .setWorkflowExecutionTimedOutEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void cancelWorkflow(
      RequestContext ctx,
      WorkflowData data,
      CancelWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    WorkflowExecutionCanceledEventAttributes a =
        new WorkflowExecutionCanceledEventAttributes()
            .setDetails(d.getDetails())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionCanceled)
            .setWorkflowExecutionCanceledEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void requestWorkflowCancellation(
      RequestContext ctx,
      WorkflowData data,
      RequestCancelWorkflowExecutionRequest cancelRequest,
      long notUsed) {
    WorkflowExecutionCancelRequestedEventAttributes a =
        new WorkflowExecutionCancelRequestedEventAttributes()
            .setIdentity(cancelRequest.getIdentity());
    HistoryEvent cancelRequested =
        new HistoryEvent()
            .setEventType(EventType.WorkflowExecutionCancelRequested)
            .setWorkflowExecutionCancelRequestedEventAttributes(a);
    ctx.addEvent(cancelRequested);
  }

  private static void scheduleActivityTask(
      RequestContext ctx,
      ActivityTaskData data,
      ScheduleActivityTaskDecisionAttributes d,
      long decisionTaskCompletedEventId)
      throws BadRequestError {
    int scheduleToCloseTimeoutSeconds = d.getScheduleToCloseTimeoutSeconds();
    int scheduleToStartTimeoutSeconds = d.getScheduleToStartTimeoutSeconds();
    RetryState retryState;
    RetryPolicy retryPolicy = d.getRetryPolicy();
    if (retryPolicy != null) {
      long expirationInterval =
          TimeUnit.SECONDS.toMillis(retryPolicy.getExpirationIntervalInSeconds());
      long expirationTime = data.store.currentTimeMillis() + expirationInterval;
      retryState = new RetryState(retryPolicy, expirationTime);
      // Override activity timeouts to allow retry policy to run up to its expiration.
      int overriddenTimeout;
      if (retryPolicy.getExpirationIntervalInSeconds() > 0) {
        overriddenTimeout = retryPolicy.getExpirationIntervalInSeconds();
      } else {
        overriddenTimeout =
            data.startWorkflowExecutionRequest.getExecutionStartToCloseTimeoutSeconds();
      }
      scheduleToCloseTimeoutSeconds = overriddenTimeout;
      scheduleToStartTimeoutSeconds = overriddenTimeout;
    } else {
      retryState = null;
    }

    ActivityTaskScheduledEventAttributes a =
        new ActivityTaskScheduledEventAttributes()
            .setInput(d.getInput())
            .setActivityId(d.getActivityId())
            .setActivityType(d.getActivityType())
            .setDomain(d.getDomain() == null ? ctx.getDomain() : d.getDomain())
            .setHeartbeatTimeoutSeconds(d.getHeartbeatTimeoutSeconds())
            .setScheduleToCloseTimeoutSeconds(scheduleToCloseTimeoutSeconds)
            .setScheduleToStartTimeoutSeconds(scheduleToStartTimeoutSeconds)
            .setStartToCloseTimeoutSeconds(d.getStartToCloseTimeoutSeconds())
            .setTaskList(d.getTaskList())
            .setRetryPolicy(retryPolicy)
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    data.scheduledEvent =
        a; // Cannot set it in onCommit as it is used in the processScheduleActivityTask
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskScheduled)
            .setActivityTaskScheduledEventAttributes(a);
    long scheduledEventId = ctx.addEvent(event);

    PollForActivityTaskResponse taskResponse =
        new PollForActivityTaskResponse()
            .setActivityType(d.getActivityType())
            .setWorkflowExecution(ctx.getExecution())
            .setActivityId(d.getActivityId())
            .setInput(d.getInput())
            .setHeartbeatTimeoutSeconds(d.getHeartbeatTimeoutSeconds())
            .setScheduleToCloseTimeoutSeconds(scheduleToCloseTimeoutSeconds)
            .setStartToCloseTimeoutSeconds(d.getStartToCloseTimeoutSeconds())
            .setScheduledTimestamp(ctx.currentTimeInNanoseconds())
            .setAttempt(0);

    TaskListId taskListId = new TaskListId(ctx.getDomain(), d.getTaskList().getName());
    ActivityTask activityTask = new ActivityTask(taskListId, taskResponse);
    ctx.addActivityTask(activityTask);
    ctx.onCommit(
        (historySize) -> {
          data.scheduledEventId = scheduledEventId;
          data.activityTask = activityTask;
          data.retryState = retryState;
        });
  }

  private static void requestActivityCancellation(
      RequestContext ctx,
      ActivityTaskData data,
      RequestCancelActivityTaskDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    ActivityTaskCancelRequestedEventAttributes a =
        new ActivityTaskCancelRequestedEventAttributes()
            .setActivityId(d.getActivityId())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskCancelRequested)
            .setActivityTaskCancelRequestedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void scheduleDecisionTask(
      RequestContext ctx,
      DecisionTaskData data,
      StartWorkflowExecutionRequest request,
      long notUsed) {
    DecisionTaskScheduledEventAttributes a =
        new DecisionTaskScheduledEventAttributes()
            .setStartToCloseTimeoutSeconds(request.getTaskStartToCloseTimeoutSeconds())
            .setTaskList(request.getTaskList())
            .setAttempt(data.attempt);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.DecisionTaskScheduled)
            .setDecisionTaskScheduledEventAttributes(a);
    long scheduledEventId = ctx.addEvent(event);
    PollForDecisionTaskResponse decisionTaskResponse = new PollForDecisionTaskResponse();
    if (data.previousStartedEventId > 0) {
      decisionTaskResponse.setPreviousStartedEventId(data.previousStartedEventId);
    }
    decisionTaskResponse.setWorkflowExecution(ctx.getExecution());
    decisionTaskResponse.setWorkflowType(request.getWorkflowType());
    decisionTaskResponse.setAttempt(data.attempt);
    TaskListId taskListId = new TaskListId(ctx.getDomain(), request.getTaskList().getName());
    DecisionTask decisionTask = new DecisionTask(taskListId, decisionTaskResponse);
    ctx.setDecisionTask(decisionTask);
    ctx.onCommit(
        (historySize) -> {
          data.scheduledEventId = scheduledEventId;
          data.decisionTask = decisionTaskResponse;
        });
  }

  private static void startDecisionTask(
      RequestContext ctx, DecisionTaskData data, PollForDecisionTaskRequest request, long notUsed) {
    DecisionTaskStartedEventAttributes a =
        new DecisionTaskStartedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.DecisionTaskStarted)
            .setDecisionTaskStartedEventAttributes(a);
    long startedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.decisionTask.setStartedEventId(startedEventId);
          DecisionTaskToken taskToken = new DecisionTaskToken(ctx.getExecutionId(), historySize);
          data.decisionTask.setTaskToken(taskToken.toBytes());
          GetWorkflowExecutionHistoryRequest getRequest =
              new GetWorkflowExecutionHistoryRequest()
                  .setDomain(request.getDomain())
                  .setExecution(ctx.getExecution());
          List<HistoryEvent> events;
          try {
            events =
                data.store
                    .getWorkflowExecutionHistory(ctx.getExecutionId(), getRequest)
                    .getHistory()
                    .getEvents();

            if (ctx.getWorkflowMutableState().getStickyExecutionAttributes() != null) {
              events = events.subList((int) data.previousStartedEventId, events.size());
            }
            // get it from pervious started event id.
          } catch (EntityNotExistsError entityNotExistsError) {
            throw new InternalServiceError(entityNotExistsError.toString());
          }
          data.decisionTask.setHistory(new History().setEvents(events));
          data.startedEventId = startedEventId;
          data.attempt++;
        });
  }

  private static void startActivityTask(
      RequestContext ctx, ActivityTaskData data, PollForActivityTaskRequest request, long notUsed) {
    ActivityTaskStartedEventAttributes a =
        new ActivityTaskStartedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    if (data.retryState != null) {
      a.setAttempt(data.retryState.getAttempt());
    }
    // Setting timestamp here as the default logic will set it to the time when it is added to the
    // history. But in the case of retry it happens only after an activity completion.
    long timestamp = TimeUnit.MILLISECONDS.toNanos(data.store.currentTimeMillis());
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskStarted)
            .setTimestamp(timestamp)
            .setActivityTaskStartedEventAttributes(a);
    long startedEventId;
    if (data.retryState == null) {
      startedEventId = ctx.addEvent(event);
    } else {
      startedEventId = NO_EVENT_ID;
    }
    ctx.onCommit(
        (historySize) -> {
          data.startedEventId = startedEventId;
          data.startedEvent = event;
          PollForActivityTaskResponse task = data.activityTask.getTask();
          task.setTaskToken(new ActivityId(ctx.getExecutionId(), task.getActivityId()).toBytes());
          task.setStartedTimestamp(timestamp);
        });
  }

  private static void completeDecisionTask(
      RequestContext ctx,
      DecisionTaskData data,
      RespondDecisionTaskCompletedRequest request,
      long notUsed) {
    DecisionTaskCompletedEventAttributes a =
        new DecisionTaskCompletedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.DecisionTaskCompleted)
            .setDecisionTaskCompletedEventAttributes(a);
    ctx.addEvent(event);
    ctx.onCommit((historySize) -> data.attempt = 0);
  }

  private static void failDecisionTask(
      RequestContext ctx,
      DecisionTaskData data,
      RespondDecisionTaskFailedRequest request,
      long notUsed) {
    DecisionTaskFailedEventAttributes a =
        new DecisionTaskFailedEventAttributes()
            .setIdentity(request.getIdentity())
            .setCause(request.getCause())
            .setDetails(request.getDetails())
            .setStartedEventId(data.startedEventId)
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.DecisionTaskFailed)
            .setDecisionTaskFailedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void timeoutDecisionTask(
      RequestContext ctx, DecisionTaskData data, Object ignored, long notUsed) {
    DecisionTaskTimedOutEventAttributes a =
        new DecisionTaskTimedOutEventAttributes()
            .setStartedEventId(data.startedEventId)
            .setTimeoutType(TimeoutType.START_TO_CLOSE)
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.DecisionTaskTimedOut)
            .setDecisionTaskTimedOutEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void completeActivityTask(
      RequestContext ctx, ActivityTaskData data, Object request, long notUsed) {
    if (data.retryState != null) {
      ctx.addEvent(data.startedEvent);
    }
    if (request instanceof RespondActivityTaskCompletedRequest) {
      completeActivityTaskByTaskToken(ctx, data, (RespondActivityTaskCompletedRequest) request);
    } else if (request instanceof RespondActivityTaskCompletedByIDRequest) {
      completeActivityTaskById(ctx, data, (RespondActivityTaskCompletedByIDRequest) request);
    } else {
      throw new IllegalArgumentException("Unknown request: " + request);
    }
  }

  private static void completeActivityTaskByTaskToken(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskCompletedRequest request) {
    ActivityTaskCompletedEventAttributes a =
        new ActivityTaskCompletedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setResult(request.getResult())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskCompleted)
            .setActivityTaskCompletedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void completeActivityTaskById(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskCompletedByIDRequest request) {
    ActivityTaskCompletedEventAttributes a =
        new ActivityTaskCompletedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setResult(request.getResult())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskCompleted)
            .setActivityTaskCompletedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static State failActivityTask(
      RequestContext ctx, ActivityTaskData data, Object request, long notUsed) {
    if (request instanceof RespondActivityTaskFailedRequest) {
      return failActivityTaskByTaskToken(ctx, data, (RespondActivityTaskFailedRequest) request);
    } else if (request instanceof RespondActivityTaskFailedByIDRequest) {
      return failActivityTaskById(ctx, data, (RespondActivityTaskFailedByIDRequest) request);
    } else {
      throw new IllegalArgumentException("Unknown request: " + request);
    }
  }

  private static State failActivityTaskByTaskToken(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskFailedRequest request) {
    if (attemptActivityRetry(ctx, request.getReason(), data)) {
      return INITIATED;
    }
    ActivityTaskFailedEventAttributes a =
        new ActivityTaskFailedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setDetails(request.getDetails())
            .setReason(request.getReason())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskFailed)
            .setActivityTaskFailedEventAttributes(a);
    ctx.addEvent(event);
    return FAILED;
  }

  private static State failActivityTaskById(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskFailedByIDRequest request) {
    if (attemptActivityRetry(ctx, request.getReason(), data)) {
      return INITIATED;
    }
    ActivityTaskFailedEventAttributes a =
        new ActivityTaskFailedEventAttributes()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setDetails(request.getDetails())
            .setReason(request.getReason())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskFailed)
            .setActivityTaskFailedEventAttributes(a);
    ctx.addEvent(event);
    return FAILED;
  }

  private static State timeoutActivityTask(
      RequestContext ctx, ActivityTaskData data, TimeoutType timeoutType, long notUsed) {
    // ScheduleToStart (queue timeout) is not retriable. Instead of the retry, a customer should set
    // a larger ScheduleToStart timeout.
    if (timeoutType != TimeoutType.SCHEDULE_TO_START
        && attemptActivityRetry(ctx, TIMEOUT_ERROR_REASON, data)) {
      return INITIATED;
    }
    ActivityTaskTimedOutEventAttributes a =
        new ActivityTaskTimedOutEventAttributes()
            .setScheduledEventId(data.scheduledEventId)
            .setDetails(data.heartbeatDetails)
            .setTimeoutType(timeoutType)
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskTimedOut)
            .setActivityTaskTimedOutEventAttributes(a);
    ctx.addEvent(event);
    return TIMED_OUT;
  }

  private static boolean attemptActivityRetry(
      RequestContext ctx, String errorReason, ActivityTaskData data) {
    if (data.retryState != null) {
      RetryState nextAttempt = data.retryState.getNextAttempt();
      data.nextBackoffIntervalSeconds =
          data.retryState.getBackoffIntervalInSeconds(errorReason, data.store.currentTimeMillis());
      if (data.nextBackoffIntervalSeconds > 0) {
        data.activityTask.getTask().setHeartbeatDetails(data.heartbeatDetails);
        ctx.onCommit(
            (historySize) -> {
              data.retryState = nextAttempt;
              data.activityTask.getTask().setAttempt(nextAttempt.getAttempt());
            });
        return true;
      } else {
        data.startedEventId = ctx.addEvent(data.startedEvent);
      }
    }
    return false;
  }

  private static void reportActivityTaskCancellation(
      RequestContext ctx, ActivityTaskData data, Object request, long notUsed) {
    byte[] details = null;
    if (request instanceof RespondActivityTaskCanceledRequest) {
      details = ((RespondActivityTaskCanceledRequest) request).getDetails();
    } else if (request instanceof RespondActivityTaskCanceledByIDRequest) {
      details = ((RespondActivityTaskCanceledByIDRequest) request).getDetails();
    }
    ActivityTaskCanceledEventAttributes a =
        new ActivityTaskCanceledEventAttributes()
            .setScheduledEventId(data.scheduledEventId)
            .setStartedEventId(data.startedEventId);
    if (details != null) {
      a.setDetails(details);
    }
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ActivityTaskCanceled)
            .setActivityTaskCanceledEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void heartbeatActivityTask(
      RequestContext nullCtx, ActivityTaskData data, byte[] details, long notUsed) {
    data.heartbeatDetails = details;
  }

  private static void startTimer(
      RequestContext ctx,
      TimerData data,
      StartTimerDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    TimerStartedEventAttributes a =
        new TimerStartedEventAttributes()
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setStartToFireTimeoutSeconds(d.getStartToFireTimeoutSeconds())
            .setTimerId(d.getTimerId());
    HistoryEvent event =
        new HistoryEvent().setEventType(EventType.TimerStarted).setTimerStartedEventAttributes(a);
    long startedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.startedEvent = a;
          data.startedEventId = startedEventId;
        });
  }

  private static void fireTimer(RequestContext ctx, TimerData data, Object ignored, long notUsed) {
    TimerFiredEventAttributes a =
        new TimerFiredEventAttributes()
            .setTimerId(data.startedEvent.getTimerId())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent().setEventType(EventType.TimerFired).setTimerFiredEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void cancelTimer(
      RequestContext ctx,
      TimerData data,
      CancelTimerDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    TimerCanceledEventAttributes a =
        new TimerCanceledEventAttributes()
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setTimerId(d.getTimerId())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        new HistoryEvent().setEventType(EventType.TimerCanceled).setTimerCanceledEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void initiateExternalSignal(
      RequestContext ctx,
      SignalExternalData data,
      SignalExternalWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes a =
        new SignalExternalWorkflowExecutionInitiatedEventAttributes();
    a.setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    if (d.isSetControl()) {
      a.setControl(d.getControl());
    }
    if (d.isSetInput()) {
      a.setInput(d.getInput());
    }
    if (d.isSetDomain()) {
      a.setDomain(d.getDomain());
    }
    if (d.isSetChildWorkflowOnly()) {
      a.setChildWorkflowOnly(d.isChildWorkflowOnly());
    }
    a.setSignalName(d.getSignalName());
    a.setWorkflowExecution(d.getExecution());
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.SignalExternalWorkflowExecutionInitiated)
            .setSignalExternalWorkflowExecutionInitiatedEventAttributes(a);
    long initiatedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.initiatedEventId = initiatedEventId;
          data.initiatedEvent = a;
        });
  }

  private static void failExternalSignal(
      RequestContext ctx,
      SignalExternalData data,
      SignalExternalWorkflowExecutionFailedCause cause,
      long notUsed) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent = data.initiatedEvent;
    SignalExternalWorkflowExecutionFailedEventAttributes a =
        new SignalExternalWorkflowExecutionFailedEventAttributes()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowExecution(initiatedEvent.getWorkflowExecution())
            .setControl(initiatedEvent.getControl())
            .setCause(cause)
            .setDomain(initiatedEvent.getDomain());
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.SignalExternalWorkflowExecutionFailed)
            .setSignalExternalWorkflowExecutionFailedEventAttributes(a);
    ctx.addEvent(event);
  }

  private static void completeExternalSignal(
      RequestContext ctx, SignalExternalData data, String runId, long notUsed) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent = data.initiatedEvent;
    WorkflowExecution signaledExecution =
        initiatedEvent.getWorkflowExecution().deepCopy().setRunId(runId);
    ExternalWorkflowExecutionSignaledEventAttributes a =
        new ExternalWorkflowExecutionSignaledEventAttributes()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowExecution(signaledExecution)
            .setControl(initiatedEvent.getControl())
            .setDomain(initiatedEvent.getDomain());
    HistoryEvent event =
        new HistoryEvent()
            .setEventType(EventType.ExternalWorkflowExecutionSignaled)
            .setExternalWorkflowExecutionSignaledEventAttributes(a);
    ctx.addEvent(event);
  }
}
