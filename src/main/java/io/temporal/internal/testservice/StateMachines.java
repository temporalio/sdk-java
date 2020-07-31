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

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.CancelTimerCommandAttributes;
import io.temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RequestCancelActivityTaskCommandAttributes;
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CancelExternalWorkflowExecutionFailedCause;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.api.enums.v1.StartChildWorkflowExecutionFailedCause;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.errordetails.v1.QueryFailedFailure;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.TimeoutFailureInfo;
import io.temporal.api.history.v1.ActivityTaskCancelRequestedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCanceledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCompletedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskFailedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskStartedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskTimedOutEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.api.history.v1.ExternalWorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.RequestCancelExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.RequestCancelExternalWorkflowExecutionInitiatedEventAttributes;
import io.temporal.api.history.v1.SignalExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.SignalExternalWorkflowExecutionInitiatedEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionInitiatedEventAttributes;
import io.temporal.api.history.v1.TimerCanceledEventAttributes;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.history.v1.TimerStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskScheduledEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskTimedOutEventAttributes;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.StatusUtils;
import io.temporal.internal.testservice.TestWorkflowStore.ActivityTask;
import io.temporal.internal.testservice.TestWorkflowStore.TaskQueueId;
import io.temporal.internal.testservice.TestWorkflowStore.WorkflowTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StateMachines {

  private static final Logger log = LoggerFactory.getLogger(StateMachines.class);

  static final int NO_EVENT_ID = -1;
  static final Duration DEFAULT_ACTIVITY_RETRY_INITIAL_INTERVAL = Durations.fromSeconds(1);
  static final double DEFAULT_ACTIVITY_RETRY_BACKOFF_COEFFICIENT = 2.0;
  static final int DEFAULT_ACTIVITY_RETRY_MAXIMUM_ATTEMPTS = 0;
  static final int DEFAULT_ACTIVITY_MAXIMUM_INTERVAL_COEFFICIENT = 100;
  public static final long DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_MILLISECONDS =
      10L * 365 * 24 * 3600 * 1000;
  public static final long DEFAULT_WORKFLOW_TASK_TIMEOUT_MILLISECONDS = 10L * 1000;
  public static final long MAX_WORKFLOW_TASK_TIMEOUT_MILLISECONDS = 60L * 1000;

  enum State {
    NONE,
    INITIATED,
    INITIATED_QUERY_ONLY,
    STARTED,
    STARTED_QUERY_ONLY,
    FAILED,
    TIMED_OUT,
    CANCELLATION_REQUESTED,
    CANCELED,
    COMPLETED,
    CONTINUED_AS_NEW,
    TERMINATED,
  }

  enum Action {
    INITIATE,
    START,
    FAIL,
    TIME_OUT,
    REQUEST_CANCELLATION,
    CANCEL,
    TERMINATE,
    UPDATE,
    COMPLETE,
    CONTINUE_AS_NEW,
    QUERY
  }

  static final class WorkflowData {
    Optional<TestServiceRetryState> retryState;
    Duration backoffStartInterval;
    String cronSchedule;
    Payloads lastCompletionResult;
    String originalExecutionRunId;
    Optional<String> continuedExecutionRunId;

    WorkflowData(
        Optional<TestServiceRetryState> retryState,
        Duration backoffStartInterval,
        String cronSchedule,
        Payloads lastCompletionResult,
        String originalExecutionRunId,
        Optional<String> continuedExecutionRunId) {
      this.retryState = retryState;
      this.backoffStartInterval = backoffStartInterval;
      this.cronSchedule = cronSchedule;
      this.lastCompletionResult = lastCompletionResult;
      this.originalExecutionRunId = originalExecutionRunId;
      this.continuedExecutionRunId = continuedExecutionRunId;
    }

    @Override
    public String toString() {
      return "WorkflowData{"
          + "retryState="
          + retryState
          + ", backoffStartInterval="
          + backoffStartInterval
          + ", cronSchedule='"
          + cronSchedule
          + '\''
          + ", lastCompletionResult="
          + lastCompletionResult
          + ", originalExecutionRunId='"
          + originalExecutionRunId
          + '\''
          + ", continuedExecutionRunId="
          + continuedExecutionRunId
          + '}';
    }
  }

  static final class WorkflowTaskData {

    final TestWorkflowStore store;

    boolean workflowCompleted;

    /** id of the last started event which completed successfully */
    long lastSuccessfulStartedEventId;

    final StartWorkflowExecutionRequest startRequest;

    long startedEventId = NO_EVENT_ID;

    PollWorkflowTaskQueueResponse.Builder workflowTask;

    /**
     * Events that are added during execution of a workflow task. They have to be buffered to be
     * added after the events generated by a workflow task. Without this the determinism will be
     * broken on replay.
     */
    final List<RequestContext> bufferedEvents = new ArrayList<>();

    long scheduledEventId = NO_EVENT_ID;

    int attempt;

    /** Query requests received during workflow task processing (after start) */
    final Map<String, TestWorkflowMutableStateImpl.ConsistentQuery> queryBuffer = new HashMap<>();

    final Map<String, TestWorkflowMutableStateImpl.ConsistentQuery> consistentQueryRequests =
        new HashMap<>();

    WorkflowTaskData(TestWorkflowStore store, StartWorkflowExecutionRequest startRequest) {
      this.store = store;
      this.startRequest = startRequest;
    }

    void clear() {
      startedEventId = NO_EVENT_ID;
      workflowTask = null;
      scheduledEventId = NO_EVENT_ID;
      attempt = 0;
    }

    @Override
    public String toString() {
      return "WorkflowTaskData{"
          + "store="
          + store
          + ", workflowCompleted="
          + workflowCompleted
          + ", lastSuccessfulStartedEventId="
          + lastSuccessfulStartedEventId
          + ", startRequest="
          + startRequest
          + ", startedEventId="
          + startedEventId
          + ", workflowTask="
          + workflowTask
          + ", bufferedEvents="
          + bufferedEvents
          + ", scheduledEventId="
          + scheduledEventId
          + ", attempt="
          + attempt
          + ", queryBuffer="
          + queryBuffer
          + ", consistentQueryRequests="
          + consistentQueryRequests
          + '}';
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
    Payloads heartbeatDetails;
    long lastHeartbeatTime;
    TestServiceRetryState retryState;
    Duration nextBackoffInterval;

    ActivityTaskData(
        TestWorkflowStore store, StartWorkflowExecutionRequest startWorkflowExecutionRequest) {
      this.store = store;
      this.startWorkflowExecutionRequest = startWorkflowExecutionRequest;
    }

    @Override
    public String toString() {
      return "ActivityTaskData{"
          + "startWorkflowExecutionRequest="
          + startWorkflowExecutionRequest
          + ", scheduledEvent="
          + scheduledEvent
          + ", activityTask="
          + activityTask
          + ", store="
          + store
          + ", scheduledEventId="
          + scheduledEventId
          + ", startedEventId="
          + startedEventId
          + ", startedEvent="
          + startedEvent
          + ", heartbeatDetails="
          + heartbeatDetails
          + ", lastHeartbeatTime="
          + lastHeartbeatTime
          + ", retryState="
          + retryState
          + ", nextBackoffInterval="
          + nextBackoffInterval
          + '}';
    }

    public int getAttempt() {
      return retryState != null ? retryState.getAttempt() : 1;
    }
  }

  static final class SignalExternalData {
    long initiatedEventId = NO_EVENT_ID;
    public SignalExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent;

    @Override
    public String toString() {
      return "SignalExternalData{"
          + "initiatedEventId="
          + initiatedEventId
          + ", initiatedEvent="
          + initiatedEvent
          + '}';
    }
  }

  static final class CancelExternalData {
    long initiatedEventId = NO_EVENT_ID;
    public RequestCancelExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent;

    @Override
    public String toString() {
      return "CancelExternalData{"
          + "initiatedEventId="
          + initiatedEventId
          + ", initiatedEvent="
          + initiatedEvent
          + '}';
    }
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

    @Override
    public String toString() {
      return "ChildWorkflowData{"
          + "service="
          + service
          + ", initiatedEvent="
          + initiatedEvent
          + ", initiatedEventId="
          + initiatedEventId
          + ", startedEventId="
          + startedEventId
          + ", execution="
          + execution
          + '}';
    }
  }

  static final class TimerData {
    TimerStartedEventAttributes startedEvent;
    public long startedEventId;

    @Override
    public String toString() {
      return "TimerData{"
          + "startedEvent="
          + startedEvent
          + ", startedEventId="
          + startedEventId
          + '}';
    }
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
        .add(STARTED, TERMINATE, TERMINATED, StateMachines::terminateWorkflow)
        .add(CANCELLATION_REQUESTED, COMPLETE, COMPLETED, StateMachines::completeWorkflow)
        .add(CANCELLATION_REQUESTED, CANCEL, CANCELED, StateMachines::cancelWorkflow)
        .add(CANCELLATION_REQUESTED, TERMINATE, TERMINATED, StateMachines::terminateWorkflow)
        .add(CANCELLATION_REQUESTED, FAIL, FAILED, StateMachines::failWorkflow)
        .add(CANCELLATION_REQUESTED, TIME_OUT, TIMED_OUT, StateMachines::timeoutWorkflow);
  }

  static StateMachine<WorkflowTaskData> newCommandStateMachine(
      TestWorkflowStore store, StartWorkflowExecutionRequest startRequest) {
    return new StateMachine<>(new WorkflowTaskData(store, startRequest))
        .add(NONE, INITIATE, INITIATED, StateMachines::scheduleWorkflowTask)
        // TODO(maxim): Uncomment once the server supports consistent query only workflow tasks
        //        .add(NONE, QUERY, INITIATED_QUERY_ONLY, StateMachines::scheduleQueryWorkflowTask)
        //        .add(INITIATED_QUERY_ONLY, QUERY, INITIATED_QUERY_ONLY,
        // StateMachines::queryWhileScheduled)
        //        .add(
        //            INITIATED_QUERY_ONLY,
        //            INITIATE,
        //            INITIATED,
        //            StateMachines::convertQueryWorkflowTaskToReal)
        //        .add(
        //            INITIATED_QUERY_ONLY,
        //            START,
        //            STARTED_QUERY_ONLY,
        //            StateMachines::startQueryOnlyWorkflowTask)
        //        .add(STARTED_QUERY_ONLY, INITIATE, STARTED_QUERY_ONLY,
        // StateMachines::needsWorkflowTask)
        //        .add(STARTED_QUERY_ONLY, QUERY, STARTED_QUERY_ONLY,
        // StateMachines::needsWorkflowTaskDueToQuery)
        //        .add(STARTED_QUERY_ONLY, FAIL, NONE, StateMachines::failQueryWorkflowTask)
        //        .add(STARTED_QUERY_ONLY, TIME_OUT, NONE, StateMachines::failQueryWorkflowTask)
        //        .add(STARTED_QUERY_ONLY, COMPLETE, NONE, StateMachines::completeQuery)
        .add(STARTED, QUERY, STARTED, StateMachines::bufferQuery)
        .add(INITIATED, INITIATE, INITIATED, StateMachines::noop)
        .add(INITIATED, QUERY, INITIATED, StateMachines::queryWhileScheduled)
        .add(INITIATED, START, STARTED, StateMachines::startWorkflowTask)
        .add(STARTED, COMPLETE, NONE, StateMachines::completeWorkflowTask)
        .add(STARTED, FAIL, NONE, StateMachines::failWorkflowTask)
        .add(STARTED, TIME_OUT, NONE, StateMachines::timeoutWorkflowTask)
        .add(STARTED, INITIATE, STARTED, StateMachines::needsWorkflowTask);
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

  public static StateMachine<CancelExternalData> newCancelExternalStateMachine() {
    return new StateMachine<>(new CancelExternalData())
        .add(NONE, INITIATE, INITIATED, StateMachines::initiateExternalCancellation)
        .add(INITIATED, FAIL, FAILED, StateMachines::failExternalCancellation)
        .add(INITIATED, START, STARTED, StateMachines::reportExternalCancellationRequested);
  }

  private static <T, A> void noop(RequestContext ctx, T data, A a, long notUsed) {}

  private static void timeoutChildWorkflow(
      RequestContext ctx, ChildWorkflowData data, RetryState retryState, long notUsed) {
    StartChildWorkflowExecutionInitiatedEventAttributes ie = data.initiatedEvent;
    ChildWorkflowExecutionTimedOutEventAttributes a =
        ChildWorkflowExecutionTimedOutEventAttributes.newBuilder()
            .setNamespace(ie.getNamespace())
            .setStartedEventId(data.startedEventId)
            .setWorkflowExecution(data.execution)
            .setWorkflowType(ie.getWorkflowType())
            .setRetryState(retryState)
            .setInitiatedEventId(data.initiatedEventId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT)
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
            .setEventType(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED)
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
            .setEventType(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED)
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
            .setEventType(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED)
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
            .setEventType(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED)
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
            .setEventType(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED)
            .setChildWorkflowExecutionCanceledEventAttributes(updatedAttr)
            .build();
    ctx.addEvent(event);
  }

  private static void initiateChildWorkflow(
      RequestContext ctx,
      ChildWorkflowData data,
      StartChildWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    StartChildWorkflowExecutionInitiatedEventAttributes.Builder a =
        StartChildWorkflowExecutionInitiatedEventAttributes.newBuilder()
            .setControl(d.getControl())
            .setInput(d.getInput())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId)
            .setNamespace(d.getNamespace().isEmpty() ? ctx.getNamespace() : d.getNamespace())
            .setWorkflowExecutionTimeout(d.getWorkflowExecutionTimeout())
            .setWorkflowRunTimeout(d.getWorkflowRunTimeout())
            .setWorkflowTaskTimeout(d.getWorkflowTaskTimeout())
            .setTaskQueue(d.getTaskQueue())
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
            .setEventType(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED)
            .setStartChildWorkflowExecutionInitiatedEventAttributes(a)
            .build();
    long initiatedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.initiatedEventId = initiatedEventId;
          data.initiatedEvent = a.build();
          StartWorkflowExecutionRequest.Builder startChild =
              StartWorkflowExecutionRequest.newBuilder()
                  .setRequestId(UUID.randomUUID().toString())
                  .setNamespace(d.getNamespace().isEmpty() ? ctx.getNamespace() : d.getNamespace())
                  .setWorkflowExecutionTimeout(d.getWorkflowExecutionTimeout())
                  .setWorkflowRunTimeout(d.getWorkflowRunTimeout())
                  .setWorkflowTaskTimeout(d.getWorkflowTaskTimeout())
                  .setTaskQueue(d.getTaskQueue())
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
          if (d.hasInput()) {
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
                    java.time.Duration.ZERO,
                    Optional.of(ctx.getWorkflowMutableState()),
                    OptionalLong.of(data.initiatedEventId),
                    Optional.empty());
              } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                  StartChildWorkflowExecutionFailedEventAttributes failRequest =
                      StartChildWorkflowExecutionFailedEventAttributes.newBuilder()
                          .setInitiatedEventId(initiatedEventId)
                          .setCause(
                              StartChildWorkflowExecutionFailedCause
                                  .START_CHILD_WORKFLOW_EXECUTION_FAILED_CAUSE_WORKFLOW_ALREADY_EXISTS)
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
    if (Durations.compare(request.getWorkflowExecutionTimeout(), Durations.ZERO) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("negative workflowExecution timeout")
          .asRuntimeException();
    }
    if (Durations.compare(request.getWorkflowRunTimeout(), Durations.ZERO) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("negative workflowRun timeout")
          .asRuntimeException();
    }
    if (Durations.compare(request.getWorkflowTaskTimeout(), Durations.ZERO) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("negative workflowTaskTimeoutSeconds")
          .asRuntimeException();
    }

    WorkflowExecutionStartedEventAttributes.Builder a =
        WorkflowExecutionStartedEventAttributes.newBuilder()
            .setWorkflowType(request.getWorkflowType())
            .setWorkflowRunTimeout(request.getWorkflowRunTimeout())
            .setWorkflowTaskTimeout(request.getWorkflowTaskTimeout())
            .setWorkflowExecutionTimeout(request.getWorkflowExecutionTimeout())
            .setIdentity(request.getIdentity())
            .setInput(request.getInput())
            .setTaskQueue(request.getTaskQueue());
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
    if (request.hasMemo()) {
      a.setMemo(request.getMemo());
    }
    if (request.hasSearchAttributes()) {
      a.setSearchAttributes((request.getSearchAttributes()));
    }
    if (request.hasHeader()) {
      a.setHeader(request.getHeader());
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
    Optional<TestWorkflowMutableState> parent = ctx.getWorkflowMutableState().getParent();
    if (parent.isPresent()) {
      ExecutionId parentExecutionId = parent.get().getExecutionId();
      a.setParentWorkflowNamespace(parentExecutionId.getNamespace());
      a.setParentWorkflowExecution(parentExecutionId.getExecution());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .setWorkflowExecutionStartedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void completeWorkflow(
      RequestContext ctx,
      WorkflowData data,
      CompleteWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    WorkflowExecutionCompletedEventAttributes.Builder a =
        WorkflowExecutionCompletedEventAttributes.newBuilder()
            .setResult(d.getResult())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED)
            .setWorkflowExecutionCompletedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void continueAsNewWorkflow(
      RequestContext ctx,
      WorkflowData data,
      ContinueAsNewWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    StartWorkflowExecutionRequest sr = ctx.getWorkflowMutableState().getStartRequest();
    WorkflowExecutionContinuedAsNewEventAttributes.Builder a =
        WorkflowExecutionContinuedAsNewEventAttributes.newBuilder();
    a.setInput(d.getInput());
    if (Durations.compare(d.getWorkflowRunTimeout(), Durations.ZERO) > 0) {
      a.setWorkflowRunTimeout(d.getWorkflowRunTimeout());
    } else {
      a.setWorkflowRunTimeout(sr.getWorkflowRunTimeout());
    }
    if (d.hasTaskQueue()) {
      a.setTaskQueue(d.getTaskQueue());
    } else {
      a.setTaskQueue(sr.getTaskQueue());
    }
    if (d.hasWorkflowType()) {
      a.setWorkflowType(d.getWorkflowType());
    } else {
      a.setWorkflowType(sr.getWorkflowType());
    }
    if (Durations.compare(d.getWorkflowTaskTimeout(), Durations.ZERO) > 0) {
      a.setWorkflowTaskTimeout(d.getWorkflowTaskTimeout());
    } else {
      a.setWorkflowTaskTimeout(sr.getWorkflowTaskTimeout());
    }
    a.setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId);
    a.setBackoffStartInterval(d.getBackoffStartInterval());
    a.setLastCompletionResult(d.getLastCompletionResult());
    a.setNewExecutionRunId(UUID.randomUUID().toString());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW)
            .setWorkflowExecutionContinuedAsNewEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void failWorkflow(
      RequestContext ctx,
      WorkflowData data,
      FailWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    WorkflowExecutionFailedEventAttributes.Builder a =
        WorkflowExecutionFailedEventAttributes.newBuilder()
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId);
    if (d.hasFailure()) {
      a.setFailure(d.getFailure());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED)
            .setWorkflowExecutionFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void timeoutWorkflow(
      RequestContext ctx, WorkflowData data, RetryState retryState, long notUsed) {
    WorkflowExecutionTimedOutEventAttributes.Builder a =
        WorkflowExecutionTimedOutEventAttributes.newBuilder().setRetryState(retryState);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT)
            .setWorkflowExecutionTimedOutEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void cancelWorkflow(
      RequestContext ctx,
      WorkflowData data,
      CancelWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    WorkflowExecutionCanceledEventAttributes.Builder a =
        WorkflowExecutionCanceledEventAttributes.newBuilder()
            .setDetails(d.getDetails())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED)
            .setWorkflowExecutionCanceledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void terminateWorkflow(
      RequestContext ctx,
      WorkflowData data,
      TerminateWorkflowExecutionRequest d,
      long workflowTaskCompletedEventId) {
    WorkflowExecutionTerminatedEventAttributes.Builder a =
        WorkflowExecutionTerminatedEventAttributes.newBuilder()
            .setDetails(d.getDetails())
            .setIdentity(d.getIdentity())
            .setReason(d.getReason());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED)
            .setWorkflowExecutionTerminatedEventAttributes(a)
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
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED)
            .setWorkflowExecutionCancelRequestedEventAttributes(a)
            .build();
    ctx.addEvent(cancelRequested);
  }

  private static void scheduleActivityTask(
      RequestContext ctx,
      ActivityTaskData data,
      ScheduleActivityTaskCommandAttributes d,
      long workflowTaskCompletedEventId) {
    RetryPolicy retryPolicy = ensureDefaultFieldsForActivityRetryPolicy(d.getRetryPolicy());
    Duration expirationInterval = d.getScheduleToCloseTimeout();
    Timestamp expirationTime = Timestamps.add(data.store.currentTime(), expirationInterval);
    TestServiceRetryState retryState = new TestServiceRetryState(retryPolicy, expirationTime);

    ActivityTaskScheduledEventAttributes.Builder a =
        ActivityTaskScheduledEventAttributes.newBuilder()
            .setInput(d.getInput())
            .setActivityId(d.getActivityId())
            .setActivityType(d.getActivityType())
            .setNamespace(d.getNamespace().isEmpty() ? ctx.getNamespace() : d.getNamespace())
            .setHeartbeatTimeout(d.getHeartbeatTimeout())
            .setRetryPolicy(retryPolicy)
            .setScheduleToCloseTimeout(d.getScheduleToCloseTimeout())
            .setScheduleToStartTimeout(d.getScheduleToStartTimeout())
            .setStartToCloseTimeout(d.getStartToCloseTimeout())
            .setTaskQueue(d.getTaskQueue())
            .setHeader(d.getHeader())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId);

    // Cannot set it in onCommit as it is used in the processScheduleActivityTask
    data.scheduledEvent = a.build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
            .setActivityTaskScheduledEventAttributes(a)
            .build();
    long scheduledEventId = ctx.addEvent(event);

    PollActivityTaskQueueResponse.Builder taskResponse =
        PollActivityTaskQueueResponse.newBuilder()
            .setWorkflowNamespace(ctx.getNamespace())
            .setWorkflowType(data.startWorkflowExecutionRequest.getWorkflowType())
            .setActivityType(d.getActivityType())
            .setWorkflowExecution(ctx.getExecution())
            .setActivityId(d.getActivityId())
            .setInput(d.getInput())
            .setHeartbeatTimeout(d.getHeartbeatTimeout())
            .setScheduleToCloseTimeout(d.getScheduleToCloseTimeout())
            .setStartToCloseTimeout(d.getStartToCloseTimeout())
            .setScheduledTime(ctx.currentTime())
            .setCurrentAttemptScheduledTime(ctx.currentTime())
            .setHeader(d.getHeader())
            .setAttempt(1);

    TaskQueueId taskQueueId = new TaskQueueId(ctx.getNamespace(), d.getTaskQueue().getName());
    ActivityTask activityTask = new ActivityTask(taskQueueId, taskResponse);
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
      RequestCancelActivityTaskCommandAttributes d,
      long workflowTaskCompletedEventId) {
    ActivityTaskCancelRequestedEventAttributes.Builder a =
        ActivityTaskCancelRequestedEventAttributes.newBuilder()
            .setScheduledEventId(d.getScheduledEventId())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED)
            .setActivityTaskCancelRequestedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void scheduleWorkflowTask(
      RequestContext ctx, WorkflowTaskData data, Object notUsedRequest, long notUsed) {
    StartWorkflowExecutionRequest request = data.startRequest;
    long scheduledEventId;
    WorkflowTaskScheduledEventAttributes a =
        WorkflowTaskScheduledEventAttributes.newBuilder()
            .setStartToCloseTimeout(request.getWorkflowTaskTimeout())
            .setTaskQueue(request.getTaskQueue())
            .setAttempt(data.attempt)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED)
            .setWorkflowTaskScheduledEventAttributes(a)
            .build();
    scheduledEventId = ctx.addEvent(event);
    PollWorkflowTaskQueueResponse.Builder workflowTaskResponse =
        PollWorkflowTaskQueueResponse.newBuilder();
    workflowTaskResponse.setWorkflowExecution(ctx.getExecution());
    workflowTaskResponse.setWorkflowType(request.getWorkflowType());
    workflowTaskResponse.setAttempt(data.attempt);
    TaskQueueId taskQueueId = new TaskQueueId(ctx.getNamespace(), request.getTaskQueue().getName());
    WorkflowTask workflowTask = new WorkflowTask(taskQueueId, workflowTaskResponse);
    ctx.setWorkflowTask(workflowTask);
    ctx.onCommit(
        (historySize) -> {
          data.scheduledEventId = scheduledEventId;
          data.workflowTask = workflowTaskResponse;
        });
  }

  private static void convertQueryWorkflowTaskToReal(
      RequestContext ctx, WorkflowTaskData data, Object notUsedRequest, long notUsed) {
    StartWorkflowExecutionRequest request = data.startRequest;
    WorkflowTaskScheduledEventAttributes a =
        WorkflowTaskScheduledEventAttributes.newBuilder()
            .setStartToCloseTimeout(request.getWorkflowTaskTimeout())
            .setTaskQueue(request.getTaskQueue())
            .setAttempt(data.attempt)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED)
            .setWorkflowTaskScheduledEventAttributes(a)
            .build();
    long scheduledEventId = ctx.addEvent(event);
    ctx.onCommit((historySize) -> data.scheduledEventId = scheduledEventId);
  }

  private static void scheduleQueryWorkflowTask(
      RequestContext ctx,
      WorkflowTaskData data,
      TestWorkflowMutableStateImpl.ConsistentQuery query,
      long notUsed) {
    ctx.lockTimer("scheduleQueryWorkflowTask");
    StartWorkflowExecutionRequest request = data.startRequest;
    PollWorkflowTaskQueueResponse.Builder workflowTaskResponse =
        PollWorkflowTaskQueueResponse.newBuilder();
    StickyExecutionAttributes stickyAttributes =
        ctx.getWorkflowMutableState().getStickyExecutionAttributes();
    String taskQueue =
        stickyAttributes == null
            ? request.getTaskQueue().getName()
            : stickyAttributes.getWorkerTaskQueue().getName();
    workflowTaskResponse.setWorkflowExecution(ctx.getExecution());
    workflowTaskResponse.setWorkflowType(request.getWorkflowType());
    workflowTaskResponse.setAttempt(data.attempt);
    TaskQueueId taskQueueId = new TaskQueueId(ctx.getNamespace(), taskQueue);
    WorkflowTask workflowTask = new WorkflowTask(taskQueueId, workflowTaskResponse);
    ctx.setWorkflowTask(workflowTask);
    ctx.onCommit(
        (historySize) -> {
          if (data.lastSuccessfulStartedEventId > 0) {
            workflowTaskResponse.setPreviousStartedEventId(data.lastSuccessfulStartedEventId);
          }
          data.scheduledEventId = NO_EVENT_ID;
          data.workflowTask = workflowTaskResponse;
          if (query != null) {
            data.consistentQueryRequests.put(query.getKey(), query);
          }
        });
  }

  private static void queryWhileScheduled(
      RequestContext ctx,
      WorkflowTaskData data,
      TestWorkflowMutableStateImpl.ConsistentQuery query,
      long notUsed) {
    data.consistentQueryRequests.put(query.getKey(), query);
  }

  private static void bufferQuery(
      RequestContext ctx,
      WorkflowTaskData data,
      TestWorkflowMutableStateImpl.ConsistentQuery query,
      long notUsed) {
    data.queryBuffer.put(query.getKey(), query);
  }

  private static void startWorkflowTask(
      RequestContext ctx,
      WorkflowTaskData data,
      PollWorkflowTaskQueueRequest request,
      long notUsed) {
    WorkflowTaskStartedEventAttributes a =
        WorkflowTaskStartedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .build();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)
            .setWorkflowTaskStartedEventAttributes(a)
            .build();
    long startedEventId = ctx.addEvent(event);
    startWorkflowTaskImpl(ctx, data, request, startedEventId, false);
  }

  private static void startQueryOnlyWorkflowTask(
      RequestContext ctx,
      WorkflowTaskData data,
      PollWorkflowTaskQueueRequest request,
      long notUsed) {
    startWorkflowTaskImpl(ctx, data, request, NO_EVENT_ID, true);
  }

  private static void startWorkflowTaskImpl(
      RequestContext ctx,
      WorkflowTaskData data,
      PollWorkflowTaskQueueRequest request,
      long startedEventId,
      boolean queryOnly) {
    ctx.onCommit(
        (historySize) -> {
          PollWorkflowTaskQueueResponse.Builder task = data.workflowTask;
          task.setStartedEventId(data.scheduledEventId + 1);
          WorkflowTaskToken taskToken = new WorkflowTaskToken(ctx.getExecutionId(), historySize);
          task.setTaskToken(taskToken.toBytes());
          GetWorkflowExecutionHistoryRequest getRequest =
              GetWorkflowExecutionHistoryRequest.newBuilder()
                  .setNamespace(request.getNamespace())
                  .setExecution(ctx.getExecution())
                  .build();
          List<HistoryEvent> events;
          events =
              data.store
                  .getWorkflowExecutionHistory(ctx.getExecutionId(), getRequest, null)
                  .getHistory()
                  .getEventsList();
          long lastEventId = events.get(events.size() - 1).getEventId();
          if (ctx.getWorkflowMutableState().getStickyExecutionAttributes() != null) {
            events = events.subList((int) data.lastSuccessfulStartedEventId, events.size());
          }
          if (queryOnly && !data.workflowCompleted) {
            events = new ArrayList<>(events); // convert list to mutable
            // Add "fake" workflow task scheduled and started if workflow is not closed
            WorkflowTaskScheduledEventAttributes scheduledAttributes =
                WorkflowTaskScheduledEventAttributes.newBuilder()
                    .setStartToCloseTimeout(data.startRequest.getWorkflowTaskTimeout())
                    .setTaskQueue(request.getTaskQueue())
                    .setAttempt(data.attempt)
                    .build();
            HistoryEvent scheduledEvent =
                HistoryEvent.newBuilder()
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED)
                    .setEventId(lastEventId + 1)
                    .setWorkflowTaskScheduledEventAttributes(scheduledAttributes)
                    .build();
            events.add(scheduledEvent);
            WorkflowTaskStartedEventAttributes startedAttributes =
                WorkflowTaskStartedEventAttributes.newBuilder()
                    .setIdentity(request.getIdentity())
                    .setScheduledEventId(lastEventId + 1)
                    .build();
            HistoryEvent startedEvent =
                HistoryEvent.newBuilder()
                    .setEventId(lastEventId + 1)
                    .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)
                    .setWorkflowTaskStartedEventAttributes(startedAttributes)
                    .build();
            events.add(startedEvent);
            task.setStartedEventId(lastEventId + 2);
          }
          // get it from pervious started event id.
          task.setHistory(History.newBuilder().addAllEvents(events));
          // Transfer the queries
          Map<String, TestWorkflowMutableStateImpl.ConsistentQuery> queries =
              data.consistentQueryRequests;
          for (Map.Entry<String, TestWorkflowMutableStateImpl.ConsistentQuery> queryEntry :
              queries.entrySet()) {
            QueryWorkflowRequest queryWorkflowRequest = queryEntry.getValue().getRequest();
            task.putQueries(queryEntry.getKey(), queryWorkflowRequest.getQuery());
          }
          if (data.lastSuccessfulStartedEventId > 0) {
            task.setPreviousStartedEventId(data.lastSuccessfulStartedEventId);
          }
          if (!queryOnly) {
            data.startedEventId = startedEventId;
            data.attempt++;
          }
        });
  }

  private static void startActivityTask(
      RequestContext ctx,
      ActivityTaskData data,
      PollActivityTaskQueueRequest request,
      long notUsed) {
    ActivityTaskStartedEventAttributes.Builder a =
        ActivityTaskStartedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    a.setAttempt(data.getAttempt());
    // Setting timestamp here as the default logic will set it to the time when it is added to the
    // history. But in the case of retry it happens only after an activity completion.
    Timestamp timestamp = data.store.currentTime();
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED)
            .setEventTime(timestamp)
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
          PollActivityTaskQueueResponse.Builder task = data.activityTask.getTask();
          task.setTaskToken(new ActivityId(ctx.getExecutionId(), data.scheduledEventId).toBytes());
          task.setStartedTime(timestamp);
        });
  }

  private static void completeWorkflowTask(
      RequestContext ctx,
      WorkflowTaskData data,
      RespondWorkflowTaskCompletedRequest request,
      long notUsed) {
    WorkflowTaskCompletedEventAttributes.Builder a =
        WorkflowTaskCompletedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED)
            .setWorkflowTaskCompletedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          if (log.isTraceEnabled()) {
            log.trace(
                "completeWorkflowTask commit workflowId="
                    + data.startRequest.getWorkflowId()
                    + ", lastSuccessfulStartedEventId="
                    + data.startedEventId);
          }
          data.lastSuccessfulStartedEventId = data.startedEventId;
          data.clear();
        });
  }

  private static void completeQuery(
      RequestContext ctx,
      WorkflowTaskData data,
      RespondWorkflowTaskCompletedRequest request,
      long notUsed) {
    Map<String, WorkflowQueryResult> responses = request.getQueryResultsMap();
    for (Map.Entry<String, WorkflowQueryResult> resultEntry : responses.entrySet()) {
      TestWorkflowMutableStateImpl.ConsistentQuery query =
          data.consistentQueryRequests.remove(resultEntry.getKey());
      if (query != null) {
        WorkflowQueryResult value = resultEntry.getValue();
        CompletableFuture<QueryWorkflowResponse> result = query.getResult();
        switch (value.getResultType()) {
          case QUERY_RESULT_TYPE_ANSWERED:
            QueryWorkflowResponse response =
                QueryWorkflowResponse.newBuilder().setQueryResult(value.getAnswer()).build();
            result.complete(response);
            break;
          case QUERY_RESULT_TYPE_FAILED:
            result.completeExceptionally(
                StatusUtils.newException(
                    Status.INTERNAL.withDescription(value.getErrorMessage()),
                    QueryFailedFailure.getDefaultInstance()));
            break;
          default:
            throw Status.INVALID_ARGUMENT
                .withDescription("Invalid query result type: " + value.getResultType())
                .asRuntimeException();
        }
      }
    }
    ctx.onCommit(
        (historySize) -> {
          data.clear();
          ctx.unlockTimer("completeQuery");
        });
  }

  private static void failQueryWorkflowTask(
      RequestContext ctx, WorkflowTaskData data, Object unused, long notUsed) {
    Iterator<Map.Entry<String, TestWorkflowMutableStateImpl.ConsistentQuery>> iterator =
        data.consistentQueryRequests.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, TestWorkflowMutableStateImpl.ConsistentQuery> entry = iterator.next();
      if (entry.getValue().getResult().isCancelled()) {
        iterator.remove();
        continue;
      }
    }
    if (!data.consistentQueryRequests.isEmpty()) {
      ctx.setNeedWorkflowTask(true);
    }
    ctx.unlockTimer("failQueryWorkflowTask");
  }

  private static void failWorkflowTask(
      RequestContext ctx,
      WorkflowTaskData data,
      RespondWorkflowTaskFailedRequest request,
      long notUsed) {
    WorkflowTaskFailedEventAttributes.Builder a =
        WorkflowTaskFailedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId)
            .setScheduledEventId(data.scheduledEventId);
    if (request.hasFailure()) {
      a.setFailure(request.getFailure());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED)
            .setWorkflowTaskFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    ctx.setNeedWorkflowTask(true);
  }

  private static void timeoutWorkflowTask(
      RequestContext ctx, WorkflowTaskData data, Object ignored, long notUsed) {
    WorkflowTaskTimedOutEventAttributes.Builder a =
        WorkflowTaskTimedOutEventAttributes.newBuilder()
            .setStartedEventId(data.startedEventId)
            .setTimeoutType(TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE)
            .setScheduledEventId(data.scheduledEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT)
            .setWorkflowTaskTimedOutEventAttributes(a)
            .build();
    ctx.addEvent(event);
    ctx.setNeedWorkflowTask(true);
  }

  private static void needsWorkflowTask(
      RequestContext requestContext,
      WorkflowTaskData workflowTaskData,
      Object notUsedRequest,
      long notUsed) {
    requestContext.setNeedWorkflowTask(true);
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
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED)
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
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED)
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
    if (!request.getFailure().hasApplicationFailureInfo()) {
      throw new IllegalArgumentException("application failure expected: " + request.getFailure());
    }
    ApplicationFailureInfo info = request.getFailure().getApplicationFailureInfo();
    RetryState retryState = attemptActivityRetry(ctx, Optional.of(info), data);
    if (retryState == RetryState.RETRY_STATE_IN_PROGRESS) {
      return INITIATED;
    }
    ActivityTaskFailedEventAttributes.Builder a =
        ActivityTaskFailedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setFailure(request.getFailure())
            .setRetryState(retryState)
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED)
            .setActivityTaskFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    return FAILED;
  }

  private static State failActivityTaskById(
      RequestContext ctx, ActivityTaskData data, RespondActivityTaskFailedByIdRequest request) {
    if (!request.getFailure().hasApplicationFailureInfo()) {
      throw new IllegalArgumentException("application failure expected: " + request.getFailure());
    }
    ApplicationFailureInfo info = request.getFailure().getApplicationFailureInfo();
    RetryState retryState = attemptActivityRetry(ctx, Optional.of(info), data);
    if (retryState == RetryState.RETRY_STATE_IN_PROGRESS) {
      return INITIATED;
    }
    ActivityTaskFailedEventAttributes.Builder a =
        ActivityTaskFailedEventAttributes.newBuilder()
            .setIdentity(request.getIdentity())
            .setScheduledEventId(data.scheduledEventId)
            .setFailure(request.getFailure())
            .setRetryState(retryState)
            .setIdentity(request.getIdentity())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED)
            .setActivityTaskFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
    return FAILED;
  }

  private static State timeoutActivityTask(
      RequestContext ctx, ActivityTaskData data, TimeoutType timeoutType, long notUsed) {
    // ScheduleToStart (queue timeout) is not retryable. Instead of the retry, a customer should set
    // a larger ScheduleToStart timeout.
    RetryState retryState;
    if (timeoutType != TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START) {
      retryState = attemptActivityRetry(ctx, Optional.empty(), data);
      if (retryState == RetryState.RETRY_STATE_IN_PROGRESS) {
        return INITIATED;
      }
    } else {
      retryState = RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE;
    }
    Failure failure;
    if (timeoutType == TimeoutType.TIMEOUT_TYPE_HEARTBEAT
        || timeoutType == TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE) {
      failure =
          newTimeoutFailure(
              TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
              Optional.ofNullable(data.heartbeatDetails),
              Optional.of(newTimeoutFailure(timeoutType, Optional.empty(), Optional.empty())));
    } else {
      failure =
          newTimeoutFailure(
              timeoutType, Optional.ofNullable(data.heartbeatDetails), Optional.empty());
    }
    ActivityTaskTimedOutEventAttributes.Builder a =
        ActivityTaskTimedOutEventAttributes.newBuilder()
            .setScheduledEventId(data.scheduledEventId)
            .setRetryState(retryState)
            .setStartedEventId(data.startedEventId)
            .setFailure(failure);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT)
            .setActivityTaskTimedOutEventAttributes(a)
            .build();
    ctx.addEvent(event);
    return TIMED_OUT;
  }

  private static Failure newTimeoutFailure(
      TimeoutType timeoutType, Optional<Payloads> lastHeartbeatDetails, Optional<Failure> cause) {
    TimeoutFailureInfo.Builder info = TimeoutFailureInfo.newBuilder().setTimeoutType(timeoutType);
    if (lastHeartbeatDetails.isPresent()) {
      info.setLastHeartbeatDetails(lastHeartbeatDetails.get());
    }
    Failure.Builder result = Failure.newBuilder().setTimeoutFailureInfo(info);
    if (cause.isPresent()) {
      result.setCause(cause.get());
    }
    return result.build();
  }

  private static RetryState attemptActivityRetry(
      RequestContext ctx, Optional<ApplicationFailureInfo> info, ActivityTaskData data) {
    if (data.retryState == null) {
      return RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET;
    }
    if (info.isPresent() && info.get().getNonRetryable()) {
      return RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE;
    }
    TestServiceRetryState nextAttempt = data.retryState.getNextAttempt();
    TestServiceRetryState.BackoffInterval backoffInterval =
        data.retryState.getBackoffIntervalInSeconds(
            info.map(i -> i.getType()), data.store.currentTime());
    if (backoffInterval.getRetryState() == RetryState.RETRY_STATE_IN_PROGRESS) {
      data.nextBackoffInterval = ProtobufTimeUtils.ToProtoDuration(backoffInterval.getInterval());
      PollActivityTaskQueueResponse.Builder task = data.activityTask.getTask();
      if (data.heartbeatDetails != null) {
        task.setHeartbeatDetails(data.heartbeatDetails);
      }
      ctx.onCommit(
          (historySize) -> {
            data.retryState = nextAttempt;
            task.setAttempt(nextAttempt.getAttempt());
            task.setCurrentAttemptScheduledTime(ctx.currentTime());
          });
    } else {
      data.startedEventId = ctx.addEvent(data.startedEvent);
      data.nextBackoffInterval = Durations.ZERO;
    }
    return backoffInterval.getRetryState();
  }

  private static void reportActivityTaskCancellation(
      RequestContext ctx, ActivityTaskData data, Object request, long notUsed) {
    Optional<Payloads> details;
    if (request instanceof RespondActivityTaskCanceledRequest) {
      {
        RespondActivityTaskCanceledRequest cr = (RespondActivityTaskCanceledRequest) request;
        details = cr.hasDetails() ? Optional.of(cr.getDetails()) : Optional.empty();
      }
    } else if (request instanceof RespondActivityTaskCanceledByIdRequest) {
      {
        RespondActivityTaskCanceledByIdRequest cr =
            (RespondActivityTaskCanceledByIdRequest) request;
        details = cr.hasDetails() ? Optional.of(cr.getDetails()) : Optional.empty();
      }
    } else {
      throw Status.INTERNAL
          .withDescription("Unexpected request type: " + request)
          .asRuntimeException();
    }
    ActivityTaskCanceledEventAttributes.Builder a =
        ActivityTaskCanceledEventAttributes.newBuilder()
            .setScheduledEventId(data.scheduledEventId)
            .setStartedEventId(data.startedEventId);
    if (details.isPresent()) {
      a.setDetails(details.get());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED)
            .setActivityTaskCanceledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void heartbeatActivityTask(
      RequestContext nullCtx, ActivityTaskData data, Payloads details, long notUsed) {
    data.heartbeatDetails = details;
  }

  private static void startTimer(
      RequestContext ctx,
      TimerData data,
      StartTimerCommandAttributes d,
      long workflowTaskCompletedEventId) {
    TimerStartedEventAttributes.Builder a =
        TimerStartedEventAttributes.newBuilder()
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId)
            .setStartToFireTimeout(d.getStartToFireTimeout())
            .setTimerId(d.getTimerId());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_TIMER_STARTED)
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
            .setEventType(EventType.EVENT_TYPE_TIMER_FIRED)
            .setTimerFiredEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void cancelTimer(
      RequestContext ctx,
      TimerData data,
      CancelTimerCommandAttributes d,
      long workflowTaskCompletedEventId) {
    TimerCanceledEventAttributes.Builder a =
        TimerCanceledEventAttributes.newBuilder()
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId)
            .setTimerId(d.getTimerId())
            .setStartedEventId(data.startedEventId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_TIMER_CANCELED)
            .setTimerCanceledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void initiateExternalSignal(
      RequestContext ctx,
      SignalExternalData data,
      SignalExternalWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    SignalExternalWorkflowExecutionInitiatedEventAttributes.Builder a =
        SignalExternalWorkflowExecutionInitiatedEventAttributes.newBuilder()
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId)
            .setControl(d.getControl())
            .setInput(d.getInput())
            .setNamespace(d.getNamespace())
            .setChildWorkflowOnly(d.getChildWorkflowOnly())
            .setSignalName(d.getSignalName())
            .setWorkflowExecution(d.getExecution());

    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED)
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
            .setEventType(EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED)
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
            .setEventType(EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED)
            .setExternalWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void initiateExternalCancellation(
      RequestContext ctx,
      CancelExternalData data,
      RequestCancelExternalWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedEventId) {
    RequestCancelExternalWorkflowExecutionInitiatedEventAttributes.Builder a =
        RequestCancelExternalWorkflowExecutionInitiatedEventAttributes.newBuilder()
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedEventId)
            .setControl(d.getControl())
            .setNamespace(d.getNamespace())
            .setChildWorkflowOnly(d.getChildWorkflowOnly())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(d.getWorkflowId())
                    .setRunId(d.getRunId())
                    .build());

    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED)
            .setRequestCancelExternalWorkflowExecutionInitiatedEventAttributes(a)
            .build();
    long initiatedEventId = ctx.addEvent(event);
    ctx.onCommit(
        (historySize) -> {
          data.initiatedEventId = initiatedEventId;
          data.initiatedEvent = a.build();
        });
  }

  private static void reportExternalCancellationRequested(
      RequestContext ctx, CancelExternalData data, String runId, long notUsed) {
    RequestCancelExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent =
        data.initiatedEvent;
    ExternalWorkflowExecutionCancelRequestedEventAttributes.Builder a =
        ExternalWorkflowExecutionCancelRequestedEventAttributes.newBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowExecution(
                WorkflowExecution.newBuilder()
                    .setRunId(runId)
                    .setWorkflowId(initiatedEvent.getWorkflowExecution().getWorkflowId())
                    .build())
            .setNamespace(initiatedEvent.getNamespace());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED)
            .setExternalWorkflowExecutionCancelRequestedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  private static void failExternalCancellation(
      RequestContext ctx,
      CancelExternalData data,
      CancelExternalWorkflowExecutionFailedCause cause,
      long notUsed) {
    RequestCancelExternalWorkflowExecutionInitiatedEventAttributes initiatedEvent =
        data.initiatedEvent;
    RequestCancelExternalWorkflowExecutionFailedEventAttributes.Builder a =
        RequestCancelExternalWorkflowExecutionFailedEventAttributes.newBuilder()
            .setInitiatedEventId(data.initiatedEventId)
            .setWorkflowExecution(initiatedEvent.getWorkflowExecution())
            .setControl(initiatedEvent.getControl())
            .setCause(cause)
            .setNamespace(initiatedEvent.getNamespace());
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED)
            .setRequestCancelExternalWorkflowExecutionFailedEventAttributes(a)
            .build();
    ctx.addEvent(event);
  }

  // Mimics the default activity retry policy of a standard Temporal server.
  static RetryPolicy ensureDefaultFieldsForActivityRetryPolicy(RetryPolicy originalPolicy) {
    Duration initialInterval =
        Durations.compare(originalPolicy.getInitialInterval(), Durations.ZERO) == 0
            ? DEFAULT_ACTIVITY_RETRY_INITIAL_INTERVAL
            : originalPolicy.getInitialInterval();

    return RetryPolicy.newBuilder()
        .setInitialInterval(initialInterval)
        .setMaximumInterval(
            Durations.compare(originalPolicy.getMaximumInterval(), Durations.ZERO) == 0
                ? Durations.fromMillis(
                    DEFAULT_ACTIVITY_MAXIMUM_INTERVAL_COEFFICIENT
                        * Durations.toMillis(initialInterval))
                : originalPolicy.getMaximumInterval())
        .setBackoffCoefficient(
            originalPolicy.getBackoffCoefficient() == 0
                ? DEFAULT_ACTIVITY_RETRY_BACKOFF_COEFFICIENT
                : originalPolicy.getBackoffCoefficient())
        .setMaximumAttempts(
            originalPolicy.getMaximumAttempts() == 0
                ? DEFAULT_ACTIVITY_RETRY_MAXIMUM_ATTEMPTS
                : originalPolicy.getMaximumAttempts())
        .build();
  }
}
