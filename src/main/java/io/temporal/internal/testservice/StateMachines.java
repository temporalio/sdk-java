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

import static io.temporal.internal.testservice.StateMachines.Action.*;
import static io.temporal.internal.testservice.StateMachines.State.*;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.internal.testservice.TestWorkflowStore.ActivityTask;
import io.temporal.internal.testservice.TestWorkflowStore.DecisionTask;
import io.temporal.internal.testservice.TestWorkflowStore.TaskListId;
import io.temporal.proto.common.ActivityTaskCancelRequestedEventAttributes;
import io.temporal.proto.common.ActivityTaskCanceledEventAttributes;
import io.temporal.proto.common.ActivityTaskCompletedEventAttributes;
import io.temporal.proto.common.ActivityTaskFailedEventAttributes;
import io.temporal.proto.common.ActivityTaskScheduledEventAttributes;
import io.temporal.proto.common.ActivityTaskStartedEventAttributes;
import io.temporal.proto.common.ActivityTaskTimedOutEventAttributes;
import io.temporal.proto.common.CancelTimerDecisionAttributes;
import io.temporal.proto.common.CancelWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.common.CompleteWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.ContinueAsNewWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.DecisionTaskCompletedEventAttributes;
import io.temporal.proto.common.DecisionTaskFailedEventAttributes;
import io.temporal.proto.common.DecisionTaskScheduledEventAttributes;
import io.temporal.proto.common.DecisionTaskStartedEventAttributes;
import io.temporal.proto.common.DecisionTaskTimedOutEventAttributes;
import io.temporal.proto.common.ExternalWorkflowExecutionSignaledEventAttributes;
import io.temporal.proto.common.FailWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.History;
import io.temporal.proto.common.HistoryEvent;
import io.temporal.proto.common.RequestCancelActivityTaskDecisionAttributes;
import io.temporal.proto.common.RetryPolicy;
import io.temporal.proto.common.ScheduleActivityTaskDecisionAttributes;
import io.temporal.proto.common.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.SignalExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.SignalExternalWorkflowExecutionInitiatedEventAttributes;
import io.temporal.proto.common.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.StartChildWorkflowExecutionInitiatedEventAttributes;
import io.temporal.proto.common.StartTimerDecisionAttributes;
import io.temporal.proto.common.TimerCanceledEventAttributes;
import io.temporal.proto.common.TimerFiredEventAttributes;
import io.temporal.proto.common.TimerStartedEventAttributes;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.proto.common.WorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.common.WorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.common.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.proto.common.WorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.WorkflowExecutionStartedEventAttributes;
import io.temporal.proto.common.WorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.enums.ChildWorkflowExecutionFailedCause;
import io.temporal.proto.enums.EventType;
import io.temporal.proto.enums.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.proto.enums.TimeoutType;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledByIdRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIdRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedByIdRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StateMachines {

  private static final Logger log = LoggerFactory.getLogger(StateMachines.class);

  private static final int NO_EVENT_ID = -1;
  private static final String TIMEOUT_ERROR_REASON = "temporalInternal:Timeout";

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
    ByteString lastCompletionResult;
    String originalExecutionRunId;
    Optional<String> continuedExecutionRunId;

    WorkflowData(
        Optional<RetryState> retryState,
        int backoffStartIntervalInSeconds,
        String cronSchedule,
        ByteString lastCompletionResult,
        String originalExecutionRunId,
        Optional<String> continuedExecutionRunId) {
      this.retryState = retryState;
      this.backoffStartIntervalInSeconds = backoffStartIntervalInSeconds;
      this.cronSchedule = cronSchedule;
      this.lastCompletionResult = lastCompletionResult;
      this.originalExecutionRunId = originalExecutionRunId;
      this.continuedExecutionRunId = continuedExecutionRunId;
    }
  }

  static final class DecisionTaskData {

    final long previousStartedEventId;

    final TestWorkflowStore store;

    long startedEventId = NO_EVENT_ID;

    PollForDecisionTaskResponse.Builder decisionTask;

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
    ByteString heartbeatDetails;
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
        ChildWorkflowExecutionTimedOutEventAttributes.newBuilder()
            .setNamespace(ie.getNamespace())
            .setStartedEventId(data.startedEventId)
            .setWorkflowExecution(data.execution)
            .setWorkflowType(ie.getWorkflowType())
            .setTimeoutType(timeoutType)
            .setInitiatedEventId(data.initiatedEventId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeChildWorkflowExecutionTimedOut)
            .setChildWorkflowExecutionTimedOutEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void startChildWorkflowFailed(
      RequestContext ctx,
      ChildWorkflowData data,
      StartChildWorkflowExecutionFailedEventAttributes a,
      long notUsed) {
    StartChildWorkflowExecutionFailedEventAttributes.Builder updatedAttr =
        a.toBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowType(data.initiatedEvent.getWorkflowType())
            .setWorkflowId(data.initiatedEvent.getWorkflowId());
    if (!data.initiatedEvent.getNamespace().isEmpty()) {
      updatedAttr.setNamespace(data.initiatedEvent.getNamespace());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeStartChildWorkflowExecutionFailed)
            .setStartChildWorkflowExecutionFailedEventAttributes(updatedAttr.build())
            .build();
    ctx.addEvent(event);
  }

  private static void childWorkflowStarted(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionStartedEventAttributes a,
      long notUsed) {
    ChildWorkflowExecutionStartedEventAttributes updatedAttr =
        a.toBuilder().setInitiatedEventId(data.initiatedEventId).build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeChildWorkflowExecutionStarted)
            .setChildWorkflowExecutionStartedEventAttributes(updatedAttr)
            .build();
    long startedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.startedEventId = startedEventId;
          data.execution = updatedAttr.getWorkflowExecution();
        });
  }

  private static void childWorkflowCompleted(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionCompletedEventAttributes a,
      long notUsed) {
    ChildWorkflowExecutionCompletedEventAttributes updatedAttr =
        a.toBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setStartedEventId(data.startedEventId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeChildWorkflowExecutionCompleted)
            .setChildWorkflowExecutionCompletedEventAttributes(updatedAttr)
            .build();
    ctx.addEvent(event);
  }

  private static void childWorkflowFailed(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionFailedEventAttributes a,
      long notUsed) {
    ChildWorkflowExecutionFailedEventAttributes.Builder updatedAttr =
        a.toBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setStartedEventId(data.startedEventId)
            .setWorkflowExecution(data.execution)
            .setWorkflowType(data.initiatedEvent.getWorkflowType());
    if (!data.initiatedEvent.getNamespace().isEmpty()) {
      updatedAttr.setNamespace(data.initiatedEvent.getNamespace());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeChildWorkflowExecutionFailed)
            .setChildWorkflowExecutionFailedEventAttributes(updatedAttr.build())
            .build();
    ctx.addEvent(event);
  }

  private static void childWorkflowCanceled(
      RequestContext ctx,
      ChildWorkflowData data,
      ChildWorkflowExecutionCanceledEventAttributes a,
      long notUsed) {
    ChildWorkflowExecutionCanceledEventAttributes updatedAttr =
        a.toBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setStartedEventId(data.startedEventId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeChildWorkflowExecutionCanceled)
            .setChildWorkflowExecutionCanceledEventAttributes(updatedAttr)
            .build();
    ctx.addEvent(event);
  }

  private static void initiateChildWorkflow(
      RequestContext ctx,
      ChildWorkflowData data,
      StartChildWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    StartChildWorkflowExecutionInitiatedEventAttributes.Builder a =
        StartChildWorkflowExecutionInitiatedEventAttributes.newBuilder()
            .setControl(d.getControl())
            .setInput(d.getInput())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setNamespace(d.getNamespace().isEmpty() ? ctx.getNamespace() : d.getNamespace())
            .setExecutionStartToCloseTimeoutSeconds(d.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(d.getTaskStartToCloseTimeoutSeconds())
            .setTaskList(d.getTaskList())
            .setWorkflowId(d.getWorkflowId())
            .setWorkflowIdReusePolicy(d.getWorkflowIdReusePolicy())
            .setWorkflowType(d.getWorkflowType())
            .setCronSchedule(d.getCronSchedule())
            .setParentClosePolicy(d.getParentClosePolicy());
    if (d.hasHeader()) {
      a.setHeader(d.getHeader());
    }
    if (d.hasMemo()) {
      a.setMemo(d.getMemo());
    }
    if (d.hasRetryPolicy()) {
      a.setRetryPolicy(d.getRetryPolicy());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeStartChildWorkflowExecutionInitiated)
            .setStartChildWorkflowExecutionInitiatedEventAttributes(a)
            .build();
    long initiatedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.initiatedEventId = initiatedEventId;
          data.initiatedEvent = a.build();
          StartWorkflowExecutionRequest.Builder startChild =
              StartWorkflowExecutionRequest.newBuilder()
                  .setNamespace(d.getNamespace().isEmpty() ? ctx.getNamespace() : d.getNamespace())
                  .setExecutionStartToCloseTimeoutSeconds(
                      d.getExecutionStartToCloseTimeoutSeconds())
                  .setTaskStartToCloseTimeoutSeconds(d.getTaskStartToCloseTimeoutSeconds())
                  .setTaskList(d.getTaskList())
                  .setWorkflowId(d.getWorkflowId())
                  .setWorkflowIdReusePolicy(d.getWorkflowIdReusePolicy())
                  .setWorkflowType(d.getWorkflowType())
                  .setCronSchedule(d.getCronSchedule());
          if (d.hasHeader()) {
            startChild.setHeader(d.getHeader());
          }
          if (d.hasMemo()) {
            startChild.setMemo(d.getMemo());
          }
          if (d.hasRetryPolicy()) {
            startChild.setRetryPolicy(d.getRetryPolicy());
          }
          if (!d.getInput().isEmpty()) {
            startChild.setInput(d.getInput());
          }
          addStartChildTask(ctx, data, initiatedEventId, startChild.build());
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
              } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                  StartChildWorkflowExecutionFailedEventAttributes failRequest =
                      StartChildWorkflowExecutionFailedEventAttributes.newBuilder()
                          .setInitiatedEventId(initiatedEventId)
                          .setCause(
                              ChildWorkflowExecutionFailedCause
                                  .ChildWorkflowExecutionFailedCauseWorkflowAlreadyRunning)
                          .build();
                  try {
                    ctx.getWorkflowMutableState()
                        .failStartChildWorkflow(data.initiatedEvent.getWorkflowId(), failRequest);
                  } catch (Throwable ee) {
                    log.error("Unexpected failure inserting failStart for a child workflow", ee);
                  }
                } else {
                  log.error("Unexpected failure starting a child workflow", e);
                }
              } catch (Exception e) {
                log.error("Unexpected failure starting a child workflow", e);
              }
            });
  }

  private static void startWorkflow(
      RequestContext ctx, WorkflowData data, StartWorkflowExecutionRequest request, long notUsed) {
    if (request.getExecutionStartToCloseTimeoutSeconds() <= 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("0 or netagive executionStartToCloseTimeoutSeconds")
          .asRuntimeException();
    }
    if (request.getTaskStartToCloseTimeoutSeconds() <= 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("0 or netagive taskStartToCloseTimeoutSeconds")
          .asRuntimeException();
    }

    WorkflowExecutionStartedEventAttributes.Builder a =
        WorkflowExecutionStartedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setTaskStartToCloseTimeoutSeconds(request.getTaskStartToCloseTimeoutSeconds())
            .setExecutionStartToCloseTimeoutSeconds(
                request.getExecutionStartToCloseTimeoutSeconds())
            .setInput(request.getInput());
    if (!request.hasWorkflowType()) {
      throw Status.INVALID_ARGUMENT.withDescription("missing workflowType").asRuntimeException();
    }
    a.setWorkflowType(request.getWorkflowType());
    if (!request.hasTaskList()) {
      throw Status.INVALID_ARGUMENT.withDescription("missing taskList").asRuntimeException();
    }
    a.setTaskList(request.getTaskList());
    if (data.retryState.isPresent()) {
      a.setAttempt(data.retryState.get().getAttempt());
    }
    a.setOriginalExecutionRunId(data.originalExecutionRunId);
    if (data.continuedExecutionRunId.isPresent()) {
      a.setContinuedExecutionRunId(data.continuedExecutionRunId.get());
    }
    if (data.lastCompletionResult != null) {
      a.setLastCompletionResult(data.lastCompletionResult);
    }
    String cronSchedule = request.getCronSchedule();
    if (!cronSchedule.trim().isEmpty()) {
      try {
        TestWorkflowMutableStateImpl.parseCron(cronSchedule);
        a.setCronSchedule(cronSchedule);
      } catch (Exception e) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Invalid cron expression \"" + cronSchedule + "\": " + e.getMessage())
            .withCause(e)
            .asRuntimeException();
      }
    }
    if (request.hasMemo()) {
      a.setMemo(request.getMemo());
    }
    if (request.hasSearchAttributes()) {
      a.setSearchAttributes((request.getSearchAttributes()));
    }
    if (request.hasHeader()) {
      a.setHeader(request.getHeader());
    }
    Optional<TestWorkflowMutableState> parent = ctx.getWorkflowMutableState().getParent();
    if (parent.isPresent()) {
      ExecutionId parentExecutionId = parent.get().getExecutionId();
      a.setParentWorkflowNamespace(parentExecutionId.getNamespace());
      a.setParentWorkflowExecution(parentExecutionId.getExecution());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionStarted)
            .setWorkflowExecutionStartedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void completeWorkflow(
      RequestContext ctx,
      WorkflowData data,
      CompleteWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    WorkflowExecutionCompletedEventAttributes.Builder a =
        WorkflowExecutionCompletedEventAttributes.newBuilder()
            .setResult(d.getResult())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionCompleted)
            .setWorkflowExecutionCompletedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void continueAsNewWorkflow(
      RequestContext ctx,
      WorkflowData data,
      ContinueAsNewWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    StartWorkflowExecutionRequest sr = ctx.getWorkflowMutableState().getStartRequest();
    WorkflowExecutionContinuedAsNewEventAttributes.Builder a =
        WorkflowExecutionContinuedAsNewEventAttributes.newBuilder();
    a.setInput(d.getInput());
    if (d.getExecutionStartToCloseTimeoutSeconds() > 0) {
      a.setExecutionStartToCloseTimeoutSeconds(d.getExecutionStartToCloseTimeoutSeconds());
    } else {
      a.setExecutionStartToCloseTimeoutSeconds(sr.getExecutionStartToCloseTimeoutSeconds());
    }
    if (d.hasTaskList()) {
      a.setTaskList(d.getTaskList());
    } else {
      a.setTaskList(sr.getTaskList());
    }
    if (d.hasWorkflowType()) {
      a.setWorkflowType(d.getWorkflowType());
    } else {
      a.setWorkflowType(sr.getWorkflowType());
    }
    if (d.getTaskStartToCloseTimeoutSeconds() > 0) {
      a.setTaskStartToCloseTimeoutSeconds(d.getTaskStartToCloseTimeoutSeconds());
    } else {
      a.setTaskStartToCloseTimeoutSeconds(sr.getTaskStartToCloseTimeoutSeconds());
    }
    a.setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    a.setBackoffStartIntervalInSeconds(d.getBackoffStartIntervalInSeconds());
    a.setLastCompletionResult(d.getLastCompletionResult());
    a.setNewExecutionRunId(UUID.randomUUID().toString());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionContinuedAsNew)
            .setWorkflowExecutionContinuedAsNewEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void failWorkflow(
      RequestContext ctx,
      WorkflowData data,
      FailWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    WorkflowExecutionFailedEventAttributes.Builder a =
        WorkflowExecutionFailedEventAttributes.newBuilder()
            .setReason(d.getReason())
            .setDetails(d.getDetails())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionFailed)
            .setWorkflowExecutionFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void timeoutWorkflow(
      RequestContext ctx, WorkflowData data, TimeoutType timeoutType, long notUsed) {
    WorkflowExecutionTimedOutEventAttributes.Builder a =
        WorkflowExecutionTimedOutEventAttributes.newBuilder().setTimeoutType(timeoutType);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionTimedOut)
            .setWorkflowExecutionTimedOutEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void cancelWorkflow(
      RequestContext ctx,
      WorkflowData data,
      CancelWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    WorkflowExecutionCanceledEventAttributes.Builder a =
        WorkflowExecutionCanceledEventAttributes.newBuilder()
            .setDetails(d.getDetails())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionCanceled)
            .setWorkflowExecutionCanceledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void requestWorkflowCancellation(
      RequestContext ctx,
      WorkflowData data,
      RequestCancelWorkflowExecutionRequest cancelRequest,
      long notUsed) {
    WorkflowExecutionCancelRequestedEventAttributes.Builder a =
        WorkflowExecutionCancelRequestedEventAttributes.newBuilder()
            .setIdentity(cancelRequest.getIdentity());
    HistoryEvent cancelRequested =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeWorkflowExecutionCancelRequested)
            .setWorkflowExecutionCancelRequestedEventAttributes(a)
            .build();
    ctx.addEvent(cancelRequested);
  }

  private static void scheduleActivityTask(
      RequestContext ctx,
      ActivityTaskData data,
      ScheduleActivityTaskDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    int scheduleToCloseTimeoutSeconds = d.getScheduleToCloseTimeoutSeconds();
    int scheduleToStartTimeoutSeconds = d.getScheduleToStartTimeoutSeconds();
    RetryState retryState;
    if (d.hasRetryPolicy()) {
      RetryPolicy retryPolicy = d.getRetryPolicy();
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

    ActivityTaskScheduledEventAttributes.Builder a =
        ActivityTaskScheduledEventAttributes.newBuilder()
            .setInput(d.getInput())
            .setActivityId(d.getActivityId())
            .setActivityType(d.getActivityType())
            .setNamespace(d.getNamespace().isEmpty() ? ctx.getNamespace() : d.getNamespace())
            .setHeartbeatTimeoutSeconds(d.getHeartbeatTimeoutSeconds())
            .setScheduleToCloseTimeoutSeconds(scheduleToCloseTimeoutSeconds)
            .setScheduleToStartTimeoutSeconds(scheduleToStartTimeoutSeconds)
            .setStartToCloseTimeoutSeconds(d.getStartToCloseTimeoutSeconds())
            .setTaskList(d.getTaskList())
            .setHeader(d.getHeader())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    if (d.hasRetryPolicy()) {
      a.setRetryPolicy(d.getRetryPolicy());
    }
    // Cannot set it in onCommit as it is used in the processScheduleActivityTask
    data.scheduledEvent = a.build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskScheduled)
            .setActivityTaskScheduledEventAttributes(a)
            .build();
    long scheduledEventId = ctx.addEvent(event);

    PollForActivityTaskResponse.Builder taskResponse =
        PollForActivityTaskResponse.newBuilder()
            .setWorkflowNamespace(ctx.getNamespace())
            .setWorkflowType(data.startWorkflowExecutionRequest.getWorkflowType())
            .setActivityType(d.getActivityType())
            .setWorkflowExecution(ctx.getExecution())
            .setActivityId(d.getActivityId())
            .setInput(d.getInput())
            .setHeartbeatTimeoutSeconds(d.getHeartbeatTimeoutSeconds())
            .setScheduleToCloseTimeoutSeconds(scheduleToCloseTimeoutSeconds)
            .setStartToCloseTimeoutSeconds(d.getStartToCloseTimeoutSeconds())
            .setScheduledTimestamp(ctx.currentTimeInNanoseconds())
            .setHeader(d.getHeader())
            .setAttempt(0);

    TaskListId taskListId = new TaskListId(ctx.getNamespace(), d.getTaskList().getName());
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
    ActivityTaskCancelRequestedEventAttributes.Builder a =
        ActivityTaskCancelRequestedEventAttributes.newBuilder()
            .setActivityId(d.getActivityId())
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskCancelRequested)
            .setActivityTaskCancelRequestedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void scheduleDecisionTask(
      RequestContext ctx,
      DecisionTaskData data,
      StartWorkflowExecutionRequest request,
      long notUsed) {
    DecisionTaskScheduledEventAttributes a =
        DecisionTaskScheduledEventAttributes.newBuilder()
            .setStartToCloseTimeoutSeconds(request.getTaskStartToCloseTimeoutSeconds())
            .setTaskList(request.getTaskList())
            .setAttempt(data.attempt)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeDecisionTaskScheduled)
            .setDecisionTaskScheduledEventAttributes(a)
            .build();
    long scheduledEventId = ctx.addEvent(event);
    PollForDecisionTaskResponse.Builder decisionTaskResponse =
        PollForDecisionTaskResponse.newBuilder();
    if (data.previousStartedEventId > 0) {
      decisionTaskResponse.setPreviousStartedEventId(data.previousStartedEventId);
    }
    decisionTaskResponse.setWorkflowExecution(ctx.getExecution());
    decisionTaskResponse.setWorkflowType(request.getWorkflowType());
    decisionTaskResponse.setAttempt(data.attempt);
    TaskListId taskListId = new TaskListId(ctx.getNamespace(), request.getTaskList().getName());
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
        DecisionTaskStartedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeDecisionTaskStarted)
            .setDecisionTaskStartedEventAttributes(a)
            .build();
    long startedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.decisionTask.setStartedEventId(startedEventId);
          DecisionTaskToken taskToken = new DecisionTaskToken(ctx.getExecutionId(), historySize);
          data.decisionTask.setTaskToken(taskToken.toBytes());
          GetWorkflowExecutionHistoryRequest getRequest =
              GetWorkflowExecutionHistoryRequest.newBuilder()
                  .setNamespace(request.getNamespace())
                  .setExecution(ctx.getExecution())
                  .build();
          List<HistoryEvent> events;
          events =
              data.store
                  .getWorkflowExecutionHistory(ctx.getExecutionId(), getRequest)
                  .getHistory()
                  .getEventsList();

          if (ctx.getWorkflowMutableState().getStickyExecutionAttributes() != null) {
            events = events.subList((int) data.previousStartedEventId, events.size());
          }
          // get it from pervious started event id.
          data.decisionTask.setHistory(History.newBuilder().addAllEvents(events));
          data.startedEventId = startedEventId;
          data.attempt++;
        });
  }

  private static void startActivityTask(
      RequestContext ctx, ActivityTaskData data, PollForActivityTaskRequest request, long notUsed) {
    ActivityTaskStartedEventAttributes.Builder a =
        ActivityTaskStartedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    if (data.retryState != null) {
      a.setAttempt(data.retryState.getAttempt());
    }
    // Setting timestamp here as the default logic will set it to the time when it is added to the
    // history. But in the case of retry it happens only after an activity completion.
    long timestamp = TimeUnit.MILLISECONDS.toNanos(data.store.currentTimeMillis());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskStarted)
            .setTimestamp(timestamp)
            .setActivityTaskStartedEventAttributes(a)
            .build();
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
          PollForActivityTaskResponse.Builder task = data.activityTask.getTask();
          task.setTaskToken(new ActivityId(ctx.getExecutionId(), task.getActivityId()).toBytes());
          task.setStartedTimestamp(timestamp);
        });
  }

  private static void completeDecisionTask(
      RequestContext ctx,
      DecisionTaskData data,
      RespondDecisionTaskCompletedRequest request,
      long notUsed) {
    DecisionTaskCompletedEventAttributes.Builder a =
        DecisionTaskCompletedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeDecisionTaskCompleted)
            .setDecisionTaskCompletedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    ctx.onCommit((historySize) -> data.attempt = 0);
  }

  private static void failDecisionTask(
      RequestContext ctx,
      DecisionTaskData data,
      RespondDecisionTaskFailedRequest request,
      long notUsed) {
    DecisionTaskFailedEventAttributes.Builder a =
        DecisionTaskFailedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setCause(request.getCause())
            .setDetails(request.getDetails())
            .setStartedEventId(data.startedEventId)
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeDecisionTaskFailed)
            .setDecisionTaskFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void timeoutDecisionTask(
      RequestContext ctx, DecisionTaskData data, Object ignored, long notUsed) {
    DecisionTaskTimedOutEventAttributes.Builder a =
        DecisionTaskTimedOutEventAttributes.newBuilder()
            .setStartedEventId(data.startedEventId)
            .setTimeoutType(TimeoutType.TimeoutTypeStartToClose)
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeDecisionTaskTimedOut)
            .setDecisionTaskTimedOutEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void completeActivityTask(
      RequestContext ctx, ActivityTaskData data, Object request, long notUsed) {
    if (data.retryState != null) {
      ctx.addEvent(data.startedEvent);
    }
    if (request instanceof RespondActivityTaskCompletedRequest) {
      completeActivityTaskByTaskToken(ctx, data, (RespondActivityTaskCompletedRequest) request);
    } else if (request instanceof RespondActivityTaskCompletedByIdRequest) {
      completeActivityTaskById(ctx, data, (RespondActivityTaskCompletedByIdRequest) request);
    } else {
      throw new IllegalArgumentException("Unknown request: " + request);
    }
  }

  private static void completeActivityTaskByTaskToken(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskCompletedRequest request) {
    ActivityTaskCompletedEventAttributes.Builder a =
        ActivityTaskCompletedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setResult(request.getResult())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskCompleted)
            .setActivityTaskCompletedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void completeActivityTaskById(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskCompletedByIdRequest request) {
    ActivityTaskCompletedEventAttributes.Builder a =
        ActivityTaskCompletedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setResult(request.getResult())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskCompleted)
            .setActivityTaskCompletedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static State failActivityTask(
      RequestContext ctx, ActivityTaskData data, Object request, long notUsed) {
    if (request instanceof RespondActivityTaskFailedRequest) {
      return failActivityTaskByTaskToken(ctx, data, (RespondActivityTaskFailedRequest) request);
    } else if (request instanceof RespondActivityTaskFailedByIdRequest) {
      return failActivityTaskById(ctx, data, (RespondActivityTaskFailedByIdRequest) request);
    } else {
      throw new IllegalArgumentException("Unknown request: " + request);
    }
  }

  private static State failActivityTaskByTaskToken(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskFailedRequest request) {
    if (attemptActivityRetry(ctx, request.getReason(), data)) {
      return INITIATED;
    }
    ActivityTaskFailedEventAttributes.Builder a =
        ActivityTaskFailedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setDetails(request.getDetails())
            .setReason(request.getReason())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskFailed)
            .setActivityTaskFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    return FAILED;
  }

  private static State failActivityTaskById(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskFailedByIdRequest request) {
    if (attemptActivityRetry(ctx, request.getReason(), data)) {
      return INITIATED;
    }
    ActivityTaskFailedEventAttributes.Builder a =
        ActivityTaskFailedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setDetails(request.getDetails())
            .setReason(request.getReason())
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskFailed)
            .setActivityTaskFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    return FAILED;
  }

  private static State timeoutActivityTask(
      RequestContext ctx, ActivityTaskData data, TimeoutType timeoutType, long notUsed) {
    // ScheduleToStart (queue timeout) is not retriable. Instead of the retry, a customer should set
    // a larger ScheduleToStart timeout.
    if (timeoutType != TimeoutType.TimeoutTypeScheduleToStart
        && attemptActivityRetry(ctx, TIMEOUT_ERROR_REASON, data)) {
      return INITIATED;
    }
    ActivityTaskTimedOutEventAttributes.Builder a =
        ActivityTaskTimedOutEventAttributes.newBuilder()
            .setScheduledEventId(data.scheduledEventId)
            .setTimeoutType(timeoutType)
            .setStartedEventId(data.startedEventId);
    if (data.heartbeatDetails != null) {
      a.setDetails(data.heartbeatDetails);
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskTimedOut)
            .setActivityTaskTimedOutEventAttributes(a)
            .build();
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
        if (data.heartbeatDetails != null) {
          data.activityTask.getTask().setHeartbeatDetails(data.heartbeatDetails);
        }
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
    ByteString details = null;
    if (request instanceof RespondActivityTaskCanceledRequest) {
      details = ((RespondActivityTaskCanceledRequest) request).getDetails();
    } else if (request instanceof RespondActivityTaskCanceledByIdRequest) {
      details = ((RespondActivityTaskCanceledByIdRequest) request).getDetails();
    }
    ActivityTaskCanceledEventAttributes.Builder a =
        ActivityTaskCanceledEventAttributes.newBuilder()
            .setScheduledEventId(data.scheduledEventId)
            .setStartedEventId(data.startedEventId);
    if (details != null) {
      a.setDetails(details);
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeActivityTaskCanceled)
            .setActivityTaskCanceledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void heartbeatActivityTask(
      RequestContext nullCtx, ActivityTaskData data, ByteString details, long notUsed) {
    data.heartbeatDetails = details;
  }

  private static void startTimer(
      RequestContext ctx,
      TimerData data,
      StartTimerDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    TimerStartedEventAttributes.Builder a =
        TimerStartedEventAttributes.newBuilder()
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setStartToFireTimeoutSeconds(d.getStartToFireTimeoutSeconds())
            .setTimerId(d.getTimerId());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeTimerStarted)
            .setTimerStartedEventAttributes(a)
            .build();
    long startedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.startedEvent = a.build();
          data.startedEventId = startedEventId;
        });
  }

  private static void fireTimer(RequestContext ctx, TimerData data, Object ignored, long notUsed) {
    TimerFiredEventAttributes.Builder a =
        TimerFiredEventAttributes.newBuilder()
            .setTimerId(data.startedEvent.getTimerId())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeTimerFired)
            .setTimerFiredEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void cancelTimer(
      RequestContext ctx,
      TimerData data,
      CancelTimerDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    TimerCanceledEventAttributes.Builder a =
        TimerCanceledEventAttributes.newBuilder()
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setTimerId(d.getTimerId())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeTimerCanceled)
            .setTimerCanceledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void initiateExternalSignal(
      RequestContext ctx,
      SignalExternalData data,
      SignalExternalWorkflowExecutionDecisionAttributes d,
      long decisionTaskCompletedEventId) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes.Builder a =
        SignalExternalWorkflowExecutionInitiatedEventAttributes.newBuilder()
            .setDecisionTaskCompletedEventId(decisionTaskCompletedEventId)
            .setControl(d.getControl())
            .setInput(d.getInput())
            .setNamespace(d.getNamespace())
            .setChildWorkflowOnly(d.getChildWorkflowOnly())
            .setSignalName(d.getSignalName())
            .setWorkflowExecution(d.getExecution());

    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeSignalExternalWorkflowExecutionInitiated)
            .setSignalExternalWorkflowExecutionInitiatedEventAttributes(a)
            .build();
    long initiatedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.initiatedEventId = initiatedEventId;
          data.initiatedEvent = a.build();
        });
  }

  private static void failExternalSignal(
      RequestContext ctx,
      SignalExternalData data,
      SignalExternalWorkflowExecutionFailedCause cause,
      long notUsed) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent = data.initiatedEvent;
    SignalExternalWorkflowExecutionFailedEventAttributes.Builder a =
        SignalExternalWorkflowExecutionFailedEventAttributes.newBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowExecution(initiatedEvent.getWorkflowExecution())
            .setControl(initiatedEvent.getControl())
            .setCause(cause)
            .setNamespace(initiatedEvent.getNamespace());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeSignalExternalWorkflowExecutionFailed)
            .setSignalExternalWorkflowExecutionFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void completeExternalSignal(
      RequestContext ctx, SignalExternalData data, String runId, long notUsed) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent = data.initiatedEvent;
    WorkflowExecution signaledExecution =
        initiatedEvent.getWorkflowExecution().toBuilder().setRunId(runId).build();
    ExternalWorkflowExecutionSignaledEventAttributes.Builder a =
        ExternalWorkflowExecutionSignaledEventAttributes.newBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowExecution(signaledExecution)
            .setControl(initiatedEvent.getControl())
            .setNamespace(initiatedEvent.getNamespace());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EventTypeExternalWorkflowExecutionSignaled)
            .setExternalWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }
}
