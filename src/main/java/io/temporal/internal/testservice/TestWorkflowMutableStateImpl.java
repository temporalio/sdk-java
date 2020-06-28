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

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;
import static io.temporal.internal.testservice.RetryState.valiateAndOverrideRetryPolicy;
import static io.temporal.internal.testservice.StateMachines.*;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.common.base.Strings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.decision.v1.CancelTimerDecisionAttributes;
import io.temporal.decision.v1.CancelWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.CompleteWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.ContinueAsNewWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.Decision;
import io.temporal.decision.v1.FailWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.RecordMarkerDecisionAttributes;
import io.temporal.decision.v1.RequestCancelActivityTaskDecisionAttributes;
import io.temporal.decision.v1.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.ScheduleActivityTaskDecisionAttributes;
import io.temporal.decision.v1.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.StartTimerDecisionAttributes;
import io.temporal.decision.v1.UpsertWorkflowSearchAttributesDecisionAttributes;
import io.temporal.enums.v1.DecisionTaskFailedCause;
import io.temporal.enums.v1.EventType;
import io.temporal.enums.v1.QueryRejectCondition;
import io.temporal.enums.v1.RetryStatus;
import io.temporal.enums.v1.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.enums.v1.TimeoutType;
import io.temporal.enums.v1.WorkflowExecutionStatus;
import io.temporal.errordetails.v1.QueryFailedFailure;
import io.temporal.failure.v1.ApplicationFailureInfo;
import io.temporal.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.history.v1.CancelTimerFailedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.history.v1.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.history.v1.HistoryEvent;
import io.temporal.history.v1.MarkerRecordedEventAttributes;
import io.temporal.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.history.v1.UpsertWorkflowSearchAttributesEventAttributes;
import io.temporal.history.v1.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.internal.common.StatusUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.StateMachines.*;
import io.temporal.query.v1.QueryRejected;
import io.temporal.query.v1.WorkflowQueryResult;
import io.temporal.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.workflowservice.v1.PollForActivityTaskRequest;
import io.temporal.workflowservice.v1.PollForActivityTaskResponseOrBuilder;
import io.temporal.workflowservice.v1.PollForDecisionTaskRequest;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import io.temporal.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCanceledByIdRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCompletedByIdRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskFailedByIdRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.workflowservice.v1.RespondDecisionTaskCompletedRequest;
import io.temporal.workflowservice.v1.RespondDecisionTaskFailedRequest;
import io.temporal.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.StartWorkflowExecutionRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Iterator;
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
import java.util.concurrent.TimeoutException;
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
  private long nextEventId = 1;
  private final Map<Long, StateMachine<ActivityTaskData>> activities = new HashMap<>();
  private final Map<String, Long> activityById = new HashMap<>();
  private final Map<Long, StateMachine<ChildWorkflowData>> childWorkflows = new HashMap<>();
  private final Map<String, StateMachine<TimerData>> timers = new HashMap<>();
  private final Map<String, StateMachine<SignalExternalData>> externalSignals = new HashMap<>();
  private final Map<String, StateMachine<CancelExternalData>> externalCancellations =
      new HashMap<>();
  private StateMachine<WorkflowData> workflow;
  /** A single decison state machine is used for the whole workflow lifecycle. */
  private final StateMachine<DecisionTaskData> decision;

  private final Map<String, CompletableFuture<QueryWorkflowResponse>> queries =
      new ConcurrentHashMap<>();
  public StickyExecutionAttributes stickyExecutionAttributes;

  /**
   * @param retryState present if workflow is a retry
   * @param backoffStartIntervalInSeconds
   * @param lastCompletionResult
   * @param parentChildInitiatedEventId id of the child initiated event in the parent history
   */
  TestWorkflowMutableStateImpl(
      StartWorkflowExecutionRequest startRequest,
      String runId,
      Optional<RetryState> retryState,
      int backoffStartIntervalInSeconds,
      Payloads lastCompletionResult,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<String> continuedExecutionRunId,
      TestWorkflowService service,
      TestWorkflowStore store) {
    startRequest = overrideStartWorkflowExecutionRequest(startRequest);
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
    this.decision = StateMachines.newDecisionStateMachine(store, startRequest);
  }

  /** Based on overrideStartWorkflowExecutionRequest from historyEngine.go */
  private StartWorkflowExecutionRequest overrideStartWorkflowExecutionRequest(
      StartWorkflowExecutionRequest r) {
    StartWorkflowExecutionRequest.Builder request =
        validateStartWorkflowExecutionRequest(r).toBuilder();
    int executionTimeoutSeconds = request.getWorkflowExecutionTimeoutSeconds();
    if (executionTimeoutSeconds == 0) {
      executionTimeoutSeconds = DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_SECONDS;
    }
    executionTimeoutSeconds =
        Math.min(executionTimeoutSeconds, DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_SECONDS);
    if (executionTimeoutSeconds != request.getWorkflowExecutionTimeoutSeconds()) {
      request.setWorkflowExecutionTimeoutSeconds(executionTimeoutSeconds);
    }

    int runTimeoutSeconds = request.getWorkflowRunTimeoutSeconds();
    if (runTimeoutSeconds == 0) {
      runTimeoutSeconds = DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_SECONDS;
    }
    runTimeoutSeconds = Math.min(runTimeoutSeconds, DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_SECONDS);
    runTimeoutSeconds = Math.min(runTimeoutSeconds, executionTimeoutSeconds);
    if (runTimeoutSeconds != request.getWorkflowRunTimeoutSeconds()) {
      request.setWorkflowRunTimeoutSeconds(runTimeoutSeconds);
    }

    int taskTimeout = request.getWorkflowTaskTimeoutSeconds();
    if (taskTimeout == 0) {
      taskTimeout = DEFAULT_WORKFLOW_TASK_TIMEOUT_SECONDS;
    }
    taskTimeout = Math.min(taskTimeout, MAX_WORKFLOW_TASK_TIMEOUT_SECONDS);
    taskTimeout = Math.min(taskTimeout, runTimeoutSeconds);

    if (taskTimeout != request.getWorkflowTaskTimeoutSeconds()) {
      request.setWorkflowTaskTimeoutSeconds(taskTimeout);
    }
    return request.build();
  }

  /** Based on validateStartWorkflowExecutionRequest from historyEngine.go */
  private StartWorkflowExecutionRequest validateStartWorkflowExecutionRequest(
      StartWorkflowExecutionRequest request) {

    if (request.getRequestId().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("Missing request ID.").asRuntimeException();
    }
    if (request.getWorkflowExecutionTimeoutSeconds() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Invalid WorkflowExecutionTimeoutSeconds.")
          .asRuntimeException();
    }
    if (request.getWorkflowRunTimeoutSeconds() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Invalid WorkflowRunTimeoutSeconds.")
          .asRuntimeException();
    }
    if (request.getWorkflowTaskTimeoutSeconds() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Invalid WorkflowTaskTimeoutSeconds.")
          .asRuntimeException();
    }
    if (!request.hasTaskQueue() || request.getTaskQueue().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("Missing Taskqueue.").asRuntimeException();
    }
    if (!request.hasWorkflowType() || request.getWorkflowType().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("Missing WorkflowType.").asRuntimeException();
    }
    if (request.hasRetryPolicy()) {
      valiateAndOverrideRetryPolicy(request.getRetryPolicy());
    }
    return request;
  }

  private void update(UpdateProcedure updater) {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    update(false, updater, stackTraceElements[2].getMethodName());
  }

  private void completeDecisionUpdate(
      UpdateProcedure updater, StickyExecutionAttributes attributes) {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    stickyExecutionAttributes = attributes;
    try {
      update(true, updater, stackTraceElements[2].getMethodName());
    } catch (RuntimeException e) {
      stickyExecutionAttributes = null;
      throw e;
    }
  }

  private void update(boolean completeDecisionUpdate, UpdateProcedure updater, String caller) {
    String callerInfo = "Decision Update from " + caller;
    lock.lock();
    LockHandle lockHandle = selfAdvancingTimer.lockTimeSkipping(callerInfo);
    try {
      if (isTerminalState()) {
        throw Status.NOT_FOUND.withDescription("Completed workflow").asRuntimeException();
      }
      boolean concurrentDecision =
          !completeDecisionUpdate && (decision.getState() == StateMachines.State.STARTED);

      RequestContext ctx = new RequestContext(clock, this, nextEventId);
      updater.apply(ctx);
      if (concurrentDecision && workflow.getState() != State.TIMED_OUT) {
        decision.getData().concurrentToDecision.add(ctx);
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
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
      case FAILED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED;
      case TIMED_OUT:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT;
      case CANCELED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED;
      case COMPLETED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
      case CONTINUED_AS_NEW:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW;
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
            DecisionTaskData data = decision.getData();
            long scheduledEventId = data.scheduledEventId;
            decision.action(StateMachines.Action.START, ctx, pollRequest, 0);
            ctx.addTimer(
                startRequest.getWorkflowTaskTimeoutSeconds(),
                () -> timeoutDecisionTask(scheduledEventId),
                "DecisionTask StartToCloseTimeout");
          });
    }
  }

  @Override
  public void completeDecisionTask(
      int historySizeFromToken, RespondDecisionTaskCompletedRequest request) {
    List<Decision> decisions = request.getDecisionsList();
    completeDecisionUpdate(
        ctx -> {
          if (ctx.getInitialEventId() != historySizeFromToken + 1) {
            throw Status.NOT_FOUND
                .withDescription(
                    "Expired decision: expectedHistorySize="
                        + historySizeFromToken
                        + ","
                        + " actualHistorySize="
                        + ctx.getInitialEventId())
                .asRuntimeException();
          }
          long decisionTaskCompletedId = ctx.getNextEventId() - 1;
          // Fail the decision if there are new events and the decision tries to complete the
          // workflow
          boolean newEvents = false;
          for (RequestContext ctx2 : decision.getData().concurrentToDecision) {
            if (!ctx2.getEvents().isEmpty()) {
              newEvents = true;
              break;
            }
          }
          if (newEvents && hasCompleteDecision(request.getDecisionsList())) {
            RespondDecisionTaskFailedRequest failedRequest =
                RespondDecisionTaskFailedRequest.newBuilder()
                    .setCause(DecisionTaskFailedCause.DECISION_TASK_FAILED_CAUSE_UNHANDLED_DECISION)
                    .setIdentity(request.getIdentity())
                    .build();
            decision.action(Action.FAIL, ctx, failedRequest, decisionTaskCompletedId);
            for (RequestContext deferredCtx : decision.getData().concurrentToDecision) {
              ctx.add(deferredCtx);
            }
            decision.getData().concurrentToDecision.clear();
            scheduleDecision(ctx);
            return;
          }
          try {
            decision.action(StateMachines.Action.COMPLETE, ctx, request, 0);
            for (Decision d : decisions) {
              processDecision(ctx, d, request.getIdentity(), decisionTaskCompletedId);
            }
            for (RequestContext deferredCtx : decision.getData().concurrentToDecision) {
              ctx.add(deferredCtx);
            }
            DecisionTaskData data = this.decision.getData();
            boolean completed =
                workflow.getState() == StateMachines.State.COMPLETED
                    || workflow.getState() == StateMachines.State.FAILED
                    || workflow.getState() == StateMachines.State.CANCELED;
            if (!completed
                && ((ctx.isNeedDecision() || !decision.getData().concurrentToDecision.isEmpty())
                    || request.getForceCreateNewDecisionTask())) {
              scheduleDecision(ctx);
            }
            decision.getData().concurrentToDecision.clear();
            Map<String, ConsistentQuery> queries = data.consistentQueryRequests;
            Map<String, WorkflowQueryResult> queryResultsMap = request.getQueryResultsMap();
            for (Map.Entry<String, WorkflowQueryResult> resultEntry : queryResultsMap.entrySet()) {
              String key = resultEntry.getKey();
              ConsistentQuery query = queries.remove(key);
              if (query != null) {
                WorkflowQueryResult result = resultEntry.getValue();
                switch (result.getResultType()) {
                  case QUERY_RESULT_TYPE_ANSWERED:
                    QueryWorkflowResponse response =
                        QueryWorkflowResponse.newBuilder()
                            .setQueryResult(result.getAnswer())
                            .build();
                    query.getResult().complete(response);
                    break;
                  case QUERY_RESULT_TYPE_FAILED:
                    query
                        .getResult()
                        .completeExceptionally(
                            StatusUtils.newException(
                                Status.INTERNAL.withDescription(result.getErrorMessage()),
                                QueryFailedFailure.getDefaultInstance()));
                    break;
                  case UNRECOGNIZED:
                    throw Status.INVALID_ARGUMENT
                        .withDescription(
                            "URECOGNIZED query result type for =" + resultEntry.getKey())
                        .asRuntimeException();
                }
              }
            }
            if (decision.getState() == State.INITIATED) {
              for (ConsistentQuery query : data.queryBuffer.values()) {
                decision.action(Action.QUERY, ctx, query, NO_EVENT_ID);
              }
            } else {
              for (ConsistentQuery consistent : data.queryBuffer.values()) {
                QueryId queryId = new QueryId(executionId, consistent.getKey());
                PollForDecisionTaskResponse.Builder task =
                    PollForDecisionTaskResponse.newBuilder()
                        .setTaskToken(queryId.toBytes())
                        .setWorkflowExecution(executionId.getExecution())
                        .setWorkflowType(startRequest.getWorkflowType())
                        .setQuery(consistent.getRequest().getQuery())
                        .setWorkflowExecutionTaskQueue(startRequest.getTaskQueue());
                TestWorkflowStore.TaskQueueId taskQueueId =
                    new TestWorkflowStore.TaskQueueId(
                        consistent.getRequest().getNamespace(),
                        stickyExecutionAttributes == null
                            ? startRequest.getTaskQueue().getName()
                            : stickyExecutionAttributes.getWorkerTaskQueue().getName());
                store.sendQueryTask(executionId, taskQueueId, task);
                this.queries.put(queryId.getQueryId(), consistent.getResult());
              }
            }
            data.queryBuffer.clear();
          } finally {
            ctx.unlockTimer();
          }
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
      case DECISION_TYPE_COMPLETE_WORKFLOW_EXECUTION:
        processCompleteWorkflowExecution(
            ctx,
            d.getCompleteWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId,
            identity);
        break;
      case DECISION_TYPE_FAIL_WORKFLOW_EXECUTION:
        processFailWorkflowExecution(
            ctx, d.getFailWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId, identity);
        break;
      case DECISION_TYPE_CANCEL_WORKFLOW_EXECUTION:
        processCancelWorkflowExecution(
            ctx, d.getCancelWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
        processContinueAsNewWorkflowExecution(
            ctx,
            d.getContinueAsNewWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId,
            identity);
        break;
      case DECISION_TYPE_SCHEDULE_ACTIVITY_TASK:
        processScheduleActivityTask(
            ctx, d.getScheduleActivityTaskDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_REQUEST_CANCEL_ACTIVITY_TASK:
        processRequestCancelActivityTask(
            ctx, d.getRequestCancelActivityTaskDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_START_TIMER:
        processStartTimer(ctx, d.getStartTimerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_CANCEL_TIMER:
        processCancelTimer(ctx, d.getCancelTimerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_START_CHILD_WORKFLOW_EXECUTION:
        processStartChildWorkflow(
            ctx, d.getStartChildWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION:
        processSignalExternalWorkflowExecution(
            ctx, d.getSignalExternalWorkflowExecutionDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_RECORD_MARKER:
        processRecordMarker(ctx, d.getRecordMarkerDecisionAttributes(), decisionTaskCompletedId);
        break;
      case DECISION_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION:
        processRequestCancelExternalWorkflowExecution(
            ctx,
            d.getRequestCancelExternalWorkflowExecutionDecisionAttributes(),
            decisionTaskCompletedId);
        break;
      case DECISION_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
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
          if (isTerminalState()) {
            return;
          }
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
            .setDecisionTaskCompletedEventId(decisionTaskCompletedId)
            .putAllDetails(attr.getDetailsMap());
    if (attr.hasHeader()) {
      marker.setHeader(attr.getHeader());
    }
    if (attr.hasFailure()) {
      marker.setFailure(attr.getFailure());
    }
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_MARKER_RECORDED)
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
              .setEventType(EventType.EVENT_TYPE_CANCEL_TIMER_FAILED)
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
    long scheduledEventId = a.getScheduledEventId();
    StateMachine<?> activity = activities.get(scheduledEventId);
    if (activity == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("ACTIVITY_UNKNOWN for scheduledEventId=" + scheduledEventId)
          .asRuntimeException();
    }
    State beforeState = activity.getState();
    activity.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, a, decisionTaskCompletedId);
    if (beforeState == StateMachines.State.INITIATED) {
      activity.action(StateMachines.Action.CANCEL, ctx, null, 0);
      activities.remove(scheduledEventId);
      ctx.setNeedDecision(true);
    }
  }

  private void processScheduleActivityTask(
      RequestContext ctx, ScheduleActivityTaskDecisionAttributes a, long decisionTaskCompletedId) {
    a = validateScheduleActivityTask(a);
    String activityId = a.getActivityId();
    Long activityScheduledEventId = activityById.get(activityId);
    if (activityScheduledEventId != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already open activity with " + activityId)
          .asRuntimeException();
    }
    StateMachine<ActivityTaskData> activity = newActivityStateMachine(store, this.startRequest);
    long activityScheduleId = ctx.getNextEventId();
    activities.put(activityScheduleId, activity);
    activityById.put(activityId, activityScheduleId);
    activity.action(StateMachines.Action.INITIATE, ctx, a, decisionTaskCompletedId);
    ActivityTaskScheduledEventAttributes scheduledEvent = activity.getData().scheduledEvent;
    int attempt = activity.getData().getAttempt();
    ctx.addTimer(
        scheduledEvent.getScheduleToCloseTimeoutSeconds(),
        () -> {
          timeoutActivity(activityScheduleId, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, attempt);
        },
        "Activity ScheduleToCloseTimeout");
    ctx.addTimer(
        scheduledEvent.getScheduleToStartTimeoutSeconds(),
        () ->
            timeoutActivity(
                activityScheduleId, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START, attempt),
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
    if (!a.hasTaskQueue() || a.getTaskQueue().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("TaskQueue is not set on decision")
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
    int runTimeout = this.startRequest.getWorkflowRunTimeoutSeconds();
    boolean validScheduleToClose = a.getScheduleToCloseTimeoutSeconds() > 0;
    boolean validScheduleToStart = a.getScheduleToStartTimeoutSeconds() > 0;
    boolean validStartToClose = a.getStartToCloseTimeoutSeconds() > 0;

    if (validScheduleToClose) {
      if (validScheduleToStart) {
        result.setScheduleToStartTimeoutSeconds(
            Math.min(a.getScheduleToStartTimeoutSeconds(), a.getScheduleToCloseTimeoutSeconds()));
      } else {
        result.setScheduleToStartTimeoutSeconds(a.getScheduleToCloseTimeoutSeconds());
      }
      if (validStartToClose) {
        result.setStartToCloseTimeoutSeconds(
            Math.min(a.getStartToCloseTimeoutSeconds(), a.getScheduleToCloseTimeoutSeconds()));
      } else {
        result.setStartToCloseTimeoutSeconds(a.getScheduleToCloseTimeoutSeconds());
      }
    } else if (validStartToClose) {
      // We are in !validScheduleToClose due to the first if above
      result.setScheduleToCloseTimeoutSeconds(runTimeout);
      if (!validScheduleToStart) {
        result.setScheduleToStartTimeoutSeconds(runTimeout);
      }
    } else {
      // Deduction failed as there's not enough information to fill in missing timeouts.
      throw Status.INVALID_ARGUMENT
          .withDescription("A valid StartToClose or ScheduleToCloseTimeout is not set on decision.")
          .asRuntimeException();
    }
    // ensure activity timeout never larger than workflow timeout
    if (runTimeout > 0) {
      if (a.getScheduleToCloseTimeoutSeconds() > runTimeout) {
        result.setScheduleToCloseTimeoutSeconds(runTimeout);
      }
      if (a.getScheduleToStartTimeoutSeconds() > runTimeout) {
        result.setScheduleToStartTimeoutSeconds(runTimeout);
      }
      if (a.getStartToCloseTimeoutSeconds() > runTimeout) {
        result.setStartToCloseTimeoutSeconds(runTimeout);
      }
      if (a.getHeartbeatTimeoutSeconds() > runTimeout) {
        result.setHeartbeatTimeoutSeconds(runTimeout);
      }
    }
    if (a.getHeartbeatTimeoutSeconds() > a.getScheduleToCloseTimeoutSeconds()) {
      result.setHeartbeatTimeoutSeconds(a.getScheduleToCloseTimeoutSeconds());
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
    if (a.hasRetryPolicy()) {
      ab.setRetryPolicy(valiateAndOverrideRetryPolicy(a.getRetryPolicy()));
    }

    // Inherit taskqueue from parent workflow execution if not provided on decision
    if (!ab.hasTaskQueue()) {
      ab.setTaskQueue(startRequest.getTaskQueue());
    }

    // Inherit workflow timeout from parent workflow execution if not provided on decision
    if (a.getWorkflowExecutionTimeoutSeconds() <= 0) {
      ab.setWorkflowExecutionTimeoutSeconds(startRequest.getWorkflowExecutionTimeoutSeconds());
    }

    // Inherit workflow timeout from parent workflow execution if not provided on decision
    if (a.getWorkflowRunTimeoutSeconds() <= 0) {
      ab.setWorkflowRunTimeoutSeconds(startRequest.getWorkflowRunTimeoutSeconds());
    }

    // Inherit workflow task timeout from parent workflow execution if not provided on decision
    if (a.getWorkflowTaskTimeoutSeconds() <= 0) {
      ab.setWorkflowTaskTimeoutSeconds(startRequest.getWorkflowTaskTimeoutSeconds());
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
          ctx.unlockTimer(); // Unlock timer associated with the decision
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
                || decision.getState() == State.NONE) {
              // timeout for a previous decision
              return;
            }
            Iterator<Map.Entry<String, ConsistentQuery>> queries =
                decision.getData().queryBuffer.entrySet().iterator();
            while (queries.hasNext()) {
              Map.Entry<String, ConsistentQuery> queryEntry = queries.next();
              if (queryEntry.getValue().getResult().isCancelled()) {
                queries.remove();
              }
            }
            decision.action(
                StateMachines.Action.TIME_OUT, ctx, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, 0);
            scheduleDecision(ctx);
            ctx.unlockTimer(); // Unlock timer associated with the decision
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
          child.action(Action.TIME_OUT, ctx, a.getRetryStatus(), 0);
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
      Optional<String> failureType;
      RetryState.BackoffInterval backoffInterval;
      if (d.getFailure().hasApplicationFailureInfo()) {
        ApplicationFailureInfo failureInfo = d.getFailure().getApplicationFailureInfo();
        if (failureInfo.getNonRetryable()) {
          backoffInterval =
              new RetryState.BackoffInterval(RetryStatus.RETRY_STATUS_NON_RETRYABLE_FAILURE);
        } else {
          failureType = Optional.of(failureInfo.getType());
          backoffInterval = rs.getBackoffIntervalInSeconds(failureType, store.currentTimeMillis());
        }
      } else {
        backoffInterval =
            new RetryState.BackoffInterval(RetryStatus.RETRY_STATUS_NON_RETRYABLE_FAILURE);
      }
      if (backoffInterval.getRetryStatus() == RetryStatus.RETRY_STATUS_IN_PROGRESS) {
        ContinueAsNewWorkflowExecutionDecisionAttributes.Builder continueAsNewAttr =
            ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder()
                .setInput(startRequest.getInput())
                .setWorkflowType(startRequest.getWorkflowType())
                .setWorkflowRunTimeoutSeconds(startRequest.getWorkflowRunTimeoutSeconds())
                .setWorkflowTaskTimeoutSeconds(startRequest.getWorkflowTaskTimeoutSeconds())
                .setBackoffStartIntervalInSeconds(backoffInterval.getIntervalSeconds());
        if (startRequest.hasTaskQueue()) {
          continueAsNewAttr.setTaskQueue(startRequest.getTaskQueue());
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
        decision.getData().workflowCompleted = true;
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
    decision.getData().workflowCompleted = true;
    if (parent.isPresent()) {
      ctx.lockTimer(); // unlocked by the parent
      ChildWorkflowExecutionFailedEventAttributes a =
          ChildWorkflowExecutionFailedEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setFailure(d.getFailure())
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
    decision.getData().workflowCompleted = true;
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
      Payloads lastCompletionResult) {
    Cron cron = parseCron(data.cronSchedule);

    Instant i = Instant.ofEpochMilli(store.currentTimeMillis());
    ZonedDateTime now = ZonedDateTime.ofInstant(i, ZoneOffset.UTC);

    ExecutionTime executionTime = ExecutionTime.forCron(cron);
    Optional<Duration> backoff = executionTime.timeToNextExecution(now);
    int backoffIntervalSeconds = roundUpToSeconds(backoff.get());

    if (backoffIntervalSeconds == 0) {
      backoff = executionTime.timeToNextExecution(now.plusSeconds(1));
      backoffIntervalSeconds = roundUpToSeconds(backoff.get()) + 1;
    }

    ContinueAsNewWorkflowExecutionDecisionAttributes continueAsNewAttr =
        ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setWorkflowType(startRequest.getWorkflowType())
            .setWorkflowRunTimeoutSeconds(startRequest.getWorkflowRunTimeoutSeconds())
            .setWorkflowTaskTimeoutSeconds(startRequest.getWorkflowTaskTimeoutSeconds())
            .setTaskQueue(startRequest.getTaskQueue())
            .setBackoffStartIntervalInSeconds(backoffIntervalSeconds)
            .setRetryPolicy(startRequest.getRetryPolicy())
            .setLastCompletionResult(lastCompletionResult)
            .build();
    workflow.action(Action.CONTINUE_AS_NEW, ctx, continueAsNewAttr, decisionTaskCompletedId);
    decision.getData().workflowCompleted = true;
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
    decision.getData().workflowCompleted = true;
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
    decision.getData().workflowCompleted = true;
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
            .setEventType(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES)
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

            int runTimeoutSeconds = startRequest.getWorkflowRunTimeoutSeconds();
            if (backoffStartIntervalInSeconds > 0) {
              runTimeoutSeconds = runTimeoutSeconds + backoffStartIntervalInSeconds;
            }
            ctx.addTimer(runTimeoutSeconds, this::timeoutWorkflow, "workflow execution timeout");
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
    decision.action(StateMachines.Action.INITIATE, ctx, startRequest, 0);
    ctx.lockTimer();
  }

  @Override
  public void startActivityTask(
      PollForActivityTaskResponseOrBuilder task, PollForActivityTaskRequest pollRequest) {
    update(
        ctx -> {
          String activityId = task.getActivityId();
          StateMachine<ActivityTaskData> activity = getActivityById(activityId);
          activity.action(StateMachines.Action.START, ctx, pollRequest, 0);
          ActivityTaskData data = activity.getData();
          int startToCloseTimeout = data.scheduledEvent.getStartToCloseTimeoutSeconds();
          int heartbeatTimeout = data.scheduledEvent.getHeartbeatTimeoutSeconds();
          long scheduledEventId = activity.getData().scheduledEventId;
          if (startToCloseTimeout > 0) {
            int attempt = data.getAttempt();
            ctx.addTimer(
                startToCloseTimeout,
                () ->
                    timeoutActivity(
                        scheduledEventId, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, attempt),
                "Activity StartToCloseTimeout");
          }
          updateHeartbeatTimer(
              ctx, scheduledEventId, activity, startToCloseTimeout, heartbeatTimeout);
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
      long activityId,
      StateMachine<ActivityTaskData> activity,
      int startToCloseTimeout,
      int heartbeatTimeout) {
    if (heartbeatTimeout > 0 && heartbeatTimeout < startToCloseTimeout) {
      ActivityTaskData data = activity.getData();
      data.lastHeartbeatTime = clock.getAsLong();
      int attempt = data.getAttempt();
      ctx.addTimer(
          heartbeatTimeout,
          () -> timeoutActivity(activityId, TimeoutType.TIMEOUT_TYPE_HEARTBEAT, attempt),
          "Activity Heartbeat Timeout");
    }
  }

  @Override
  public void completeActivityTask(
      long scheduledEventId, RespondActivityTaskCompletedRequest request) {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(scheduledEventId);
          activity.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          removeActivity(scheduledEventId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void completeActivityTaskById(
      String activityId, RespondActivityTaskCompletedByIdRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivityById(activityId);
          activity.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          removeActivity(activity.getData().scheduledEventId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void failActivityTask(long scheduledEventId, RespondActivityTaskFailedRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivity(scheduledEventId);
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          if (isTerminalState(activity.getState())) {
            removeActivity(scheduledEventId);
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
    int attempt = data.getAttempt();
    ctx.addTimer(
        data.nextBackoffIntervalSeconds,
        () -> {
          // Timers are not removed, so skip if it is not for this attempt.
          if (activity.getState() != State.INITIATED && data.getAttempt() != attempt) {
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
          StateMachine<ActivityTaskData> activity = getActivityById(activityId);
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          if (isTerminalState(activity.getState())) {
            removeActivity(activity.getData().scheduledEventId);
            scheduleDecision(ctx);
          } else {
            addActivityRetryTimer(ctx, activity);
          }
          ctx.unlockTimer();
        });
  }

  @Override
  public void cancelActivityTask(
      long scheduledEventId, RespondActivityTaskCanceledRequest request) {
    update(
        ctx -> {
          StateMachine<?> activity = getActivity(scheduledEventId);
          activity.action(StateMachines.Action.CANCEL, ctx, request, 0);
          removeActivity(scheduledEventId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public void cancelActivityTaskById(
      String activityId, RespondActivityTaskCanceledByIdRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivityById(activityId);
          activity.action(StateMachines.Action.CANCEL, ctx, request, 0);
          removeActivity(activity.getData().scheduledEventId);
          scheduleDecision(ctx);
          ctx.unlockTimer();
        });
  }

  @Override
  public boolean heartbeatActivityTask(long scheduledEventId, Payloads details) {
    AtomicBoolean result = new AtomicBoolean();
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getActivity(scheduledEventId);
          if (activity.getState() != State.STARTED) {
            throw Status.NOT_FOUND
                .withDescription("Activity is in " + activity.getState() + "  state")
                .asRuntimeException();
          }
          activity.action(StateMachines.Action.UPDATE, ctx, details, 0);
          if (activity.getState() == StateMachines.State.CANCELLATION_REQUESTED) {
            result.set(true);
          }
          ActivityTaskData data = activity.getData();
          data.lastHeartbeatTime = clock.getAsLong();
          int startToCloseTimeout = data.scheduledEvent.getStartToCloseTimeoutSeconds();
          int heartbeatTimeout = data.scheduledEvent.getHeartbeatTimeoutSeconds();
          updateHeartbeatTimer(
              ctx, scheduledEventId, activity, startToCloseTimeout, heartbeatTimeout);
        });
    return result.get();
  }

  @Override
  public boolean heartbeatActivityTaskById(String id, Payloads details) {
    StateMachine<ActivityTaskData> activity = getActivityById(id);
    return heartbeatActivityTask(activity.getData().scheduledEventId, details);
  }

  private void timeoutActivity(long scheduledEventId, TimeoutType timeoutType, int timeoutAttempt) {
    boolean unlockTimer = true;
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity = getActivity(scheduledEventId);

            int attempt = activity.getData().getAttempt();
            if (timeoutAttempt != attempt
                || (activity.getState() != State.INITIATED
                    && activity.getState() != State.STARTED)) {
              throw Status.NOT_FOUND.withDescription("Outdated timer").asRuntimeException();
            }
            if (timeoutType == TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START
                && activity.getState() != StateMachines.State.INITIATED) {
              throw Status.INTERNAL.withDescription("Not in INITIATED").asRuntimeException();
            }
            if (timeoutType == TimeoutType.TIMEOUT_TYPE_HEARTBEAT) {
              // Deal with timers which are never cancelled
              long heartbeatTimeout =
                  TimeUnit.SECONDS.toMillis(
                      activity.getData().scheduledEvent.getHeartbeatTimeoutSeconds());
              if (clock.getAsLong() - activity.getData().lastHeartbeatTime < heartbeatTimeout) {
                throw Status.NOT_FOUND.withDescription("Timer fired earlier").asRuntimeException();
              }
            }
            activity.action(StateMachines.Action.TIME_OUT, ctx, timeoutType, 0);
            if (isTerminalState(activity.getState())) {
              removeActivity(scheduledEventId);
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
        selfAdvancingTimer.unlockTimeSkipping("timeoutActivity: " + scheduledEventId);
      }
    }
  }

  // TODO(maxim): Add workflow retry on run timeout
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
            // TODO(maxim): real retry status
            workflow.action(
                StateMachines.Action.TIME_OUT, ctx, RetryStatus.RETRY_STATUS_TIMEOUT, 0);
            decision.getData().workflowCompleted = true;
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
              .setRetryStatus(RetryStatus.RETRY_STATUS_TIMEOUT) // TODO(maxim): Real status
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
  public QueryWorkflowResponse query(QueryWorkflowRequest queryRequest, long deadline) {
    WorkflowExecutionStatus status = getWorkflowExecutionStatus();
    if (status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        && queryRequest.getQueryRejectCondition() != null) {
      boolean rejectNotOpen =
          queryRequest.getQueryRejectCondition()
              == QueryRejectCondition.QUERY_REJECT_CONDITION_NOT_OPEN;
      boolean rejectNotCompletedCleanly =
          queryRequest.getQueryRejectCondition()
                  == QueryRejectCondition.QUERY_REJECT_CONDITION_NOT_COMPLETED_CLEANLY
              && status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
      if (rejectNotOpen || rejectNotCompletedCleanly) {
        return QueryWorkflowResponse.newBuilder()
            .setQueryRejected(QueryRejected.newBuilder().setStatus(status))
            .build();
      }
    }
    lock.lock();
    boolean safeToDispatchDirectly =
        isTerminalState()
            || (decision.getState() != State.INITIATED && decision.getState() != State.STARTED);

    if (safeToDispatchDirectly) {
      return directQuery(queryRequest, deadline);
    } else {
      return stronglyConsistentQuery(queryRequest, deadline);
    }
  }

  private QueryWorkflowResponse directQuery(QueryWorkflowRequest queryRequest, long deadline) {
    CompletableFuture<QueryWorkflowResponse> result = new CompletableFuture<>();
    try {
      QueryId queryId = new QueryId(executionId);
      PollForDecisionTaskResponse.Builder task =
          PollForDecisionTaskResponse.newBuilder()
              .setTaskToken(queryId.toBytes())
              .setWorkflowExecution(executionId.getExecution())
              .setWorkflowType(startRequest.getWorkflowType())
              .setQuery(queryRequest.getQuery())
              .setWorkflowExecutionTaskQueue(startRequest.getTaskQueue());
      TestWorkflowStore.TaskQueueId taskQueueId =
          new TestWorkflowStore.TaskQueueId(
              queryRequest.getNamespace(),
              stickyExecutionAttributes == null
                  ? startRequest.getTaskQueue().getName()
                  : stickyExecutionAttributes.getWorkerTaskQueue().getName());
      queries.put(queryId.getQueryId(), result);
      store.sendQueryTask(executionId, taskQueueId, task);
    } finally {
      lock.unlock(); // locked in the query method
    }
    try {
      return result.get(deadline, TimeUnit.MILLISECONDS);
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
    } catch (TimeoutException e) {
      throw Status.DEADLINE_EXCEEDED
          .withCause(e)
          .withDescription("Query deadline of " + deadline + "milliseconds exceeded")
          .asRuntimeException();
    }
  }

  static class ConsistentQuery {
    private final String key = UUID.randomUUID().toString();
    private final QueryWorkflowRequest request;
    private final CompletableFuture<QueryWorkflowResponse> result = new CompletableFuture<>();

    private ConsistentQuery(QueryWorkflowRequest request) {
      this.request = request;
    }

    public QueryWorkflowRequest getRequest() {
      return request;
    }

    public CompletableFuture<QueryWorkflowResponse> getResult() {
      return result;
    }

    public String getKey() {
      return key;
    }

    @Override
    public String toString() {
      return "ConsistentQuery{"
          + "key='"
          + key
          + '\''
          + ", request="
          + request
          + ", result="
          + result
          + '}';
    }
  }

  private QueryWorkflowResponse stronglyConsistentQuery(
      QueryWorkflowRequest queryRequest, long deadline) {
    ConsistentQuery consistentQuery = new ConsistentQuery(queryRequest);
    try {
      update(ctx -> decision.action(Action.QUERY, ctx, consistentQuery, 0));
    } finally {
      // Locked in the query method
      lock.unlock();
    }
    CompletableFuture<QueryWorkflowResponse> result = consistentQuery.getResult();
    return getQueryWorkflowResponse(deadline, result);
  }

  private QueryWorkflowResponse getQueryWorkflowResponse(
      long deadline, CompletableFuture<QueryWorkflowResponse> result) {
    try {
      return result.get(deadline, TimeUnit.MILLISECONDS);
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
    } catch (TimeoutException e) {
      result.cancel(true);
      throw Status.DEADLINE_EXCEEDED
          .withCause(e)
          .withDescription("query deadline exceeded")
          .asRuntimeException();
    }
  }

  @Override
  public void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest) {
    CompletableFuture<QueryWorkflowResponse> result = queries.remove(queryId.getQueryId());
    if (result == null) {
      throw Status.NOT_FOUND
          .withDescription("Unknown query id: " + queryId.getQueryId())
          .asRuntimeException();
    }
    if (result.isCancelled()) {
      // query already timed out
      return;
    }
    switch (completeRequest.getCompletedType()) {
      case QUERY_RESULT_TYPE_ANSWERED:
        QueryWorkflowResponse response =
            QueryWorkflowResponse.newBuilder()
                .setQueryResult(completeRequest.getQueryResult())
                .build();
        result.complete(response);
        break;
      case QUERY_RESULT_TYPE_FAILED:
        StatusRuntimeException error =
            StatusUtils.newException(
                Status.INVALID_ARGUMENT.withDescription(completeRequest.getErrorMessage()),
                QueryFailedFailure.getDefaultInstance());
        result.completeExceptionally(error);
        break;
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
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
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
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
            .setWorkflowExecutionSignaledEventAttributes(a)
            .build();
    ctx.addEvent(executionSignaled);
  }

  private StateMachine<ActivityTaskData> getActivityById(String activityId) {
    Long scheduledEventId = activityById.get(activityId);
    if (scheduledEventId == null) {
      throw Status.NOT_FOUND
          .withDescription("unknown activityId: " + activityId)
          .asRuntimeException();
    }
    return getActivity(scheduledEventId);
  }

  private void removeActivity(long scheduledEventId) {
    StateMachine<ActivityTaskData> activity = activities.remove(scheduledEventId);
    if (activity == null) {
      return;
    }
    activityById.remove(activity.getData().scheduledEvent.getActivityId());
  }

  private StateMachine<ActivityTaskData> getActivity(long scheduledEventId) {
    StateMachine<ActivityTaskData> activity = activities.get(scheduledEventId);
    if (activity == null) {
      throw Status.NOT_FOUND
          .withDescription("unknown activity with scheduledEventId: " + scheduledEventId)
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
