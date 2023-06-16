/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.testservice;

import static io.temporal.internal.testservice.CronUtils.getBackoffInterval;
import static io.temporal.internal.testservice.StateMachines.DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_MILLISECONDS;
import static io.temporal.internal.testservice.StateMachines.DEFAULT_WORKFLOW_TASK_TIMEOUT_MILLISECONDS;
import static io.temporal.internal.testservice.StateMachines.MAX_WORKFLOW_TASK_TIMEOUT_MILLISECONDS;
import static io.temporal.internal.testservice.StateMachines.NO_EVENT_ID;
import static io.temporal.internal.testservice.StateMachines.newActivityStateMachine;
import static io.temporal.internal.testservice.TestServiceRetryState.validateAndOverrideRetryPolicy;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.command.v1.CancelTimerCommandAttributes;
import io.temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ProtocolMessageCommandAttributes;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.command.v1.RequestCancelActivityTaskCommandAttributes;
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.command.v1.UpsertWorkflowSearchAttributesCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.errordetails.v1.QueryFailedFailure;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.UpsertWorkflowSearchAttributesEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.query.v1.QueryRejected;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.update.v1.*;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflow.v1.PendingChildExecutionInfo;
import io.temporal.api.workflow.v1.WorkflowExecutionConfig;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ServerFailure;
import io.temporal.internal.common.ProtoEnumNameUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.StateMachines.Action;
import io.temporal.internal.testservice.StateMachines.ActivityTaskData;
import io.temporal.internal.testservice.StateMachines.CancelExternalData;
import io.temporal.internal.testservice.StateMachines.ChildWorkflowData;
import io.temporal.internal.testservice.StateMachines.SignalExternalData;
import io.temporal.internal.testservice.StateMachines.State;
import io.temporal.internal.testservice.StateMachines.TimerData;
import io.temporal.internal.testservice.StateMachines.UpdateWorkflowExecutionData;
import io.temporal.internal.testservice.StateMachines.WorkflowData;
import io.temporal.internal.testservice.StateMachines.WorkflowTaskData;
import io.temporal.serviceclient.StatusUtils;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestWorkflowMutableStateImpl implements TestWorkflowMutableState {

  /**
   * If the implementation throws an exception, changes accumulated in the RequestContext will not
   * be committed.
   */
  @FunctionalInterface
  private interface UpdateProcedure {
    void apply(RequestContext ctx);
  }

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowMutableStateImpl.class);

  private final Lock lock = new ReentrantLock();
  private final SelfAdvancingTimer timerService;
  private final LongSupplier clock;
  private final ExecutionId executionId;
  /** Parent workflow if this workflow was started as a child workflow. */
  private final Optional<TestWorkflowMutableState> parent;

  private final OptionalLong parentChildInitiatedEventId;
  private final TestWorkflowStore store;
  private final TestVisibilityStore visibilityStore;
  private final TestWorkflowService service;
  private final CommandVerifier commandVerifier;

  private final StartWorkflowExecutionRequest startRequest;
  private long nextEventId = 1;
  private final Map<Long, StateMachine<ActivityTaskData>> activities = new HashMap<>();
  private final Map<String, Long> activityById = new HashMap<>();
  private final Map<Long, StateMachine<ChildWorkflowData>> childWorkflows = new HashMap<>();
  private final Map<String, StateMachine<TimerData>> timers = new HashMap<>();
  private final Map<String, StateMachine<SignalExternalData>> externalSignals = new HashMap<>();
  private final Map<String, StateMachine<CancelExternalData>> externalCancellations =
      new HashMap<>();
  private final Map<String, StateMachine<UpdateWorkflowExecutionData>> updates = new HashMap<>();
  private final StateMachine<WorkflowData> workflow;
  /** A single workflow task state machine is used for the whole workflow lifecycle. */
  private final StateMachine<WorkflowTaskData> workflowTaskStateMachine;

  private final Map<String, CompletableFuture<QueryWorkflowResponse>> queries =
      new ConcurrentHashMap<>();
  public StickyExecutionAttributes stickyExecutionAttributes;

  /**
   * @param retryState present if workflow is a retry
   * @param parentChildInitiatedEventId id of the child initiated event in the parent history
   */
  TestWorkflowMutableStateImpl(
      StartWorkflowExecutionRequest startRequest,
      String firstExecutionRunId,
      String runId,
      Optional<TestServiceRetryState> retryState,
      Duration backoffStartInterval,
      Payloads lastCompletionResult,
      Optional<Failure> lastFailure,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<String> continuedExecutionRunId,
      TestWorkflowService service,
      TestWorkflowStore store,
      TestVisibilityStore visibilityStore,
      SelfAdvancingTimer selfAdvancingTimer) {
    this.store = store;
    this.visibilityStore = visibilityStore;
    this.service = service;
    this.commandVerifier = new CommandVerifier(visibilityStore);
    startRequest = overrideStartWorkflowExecutionRequest(startRequest);
    this.startRequest = startRequest;
    this.executionId =
        new ExecutionId(startRequest.getNamespace(), startRequest.getWorkflowId(), runId);
    this.parent = parent;
    this.parentChildInitiatedEventId = parentChildInitiatedEventId;
    this.timerService = selfAdvancingTimer;
    this.clock = selfAdvancingTimer.getClock();
    WorkflowData data =
        new WorkflowData(
            retryState,
            ProtobufTimeUtils.toProtoDuration(backoffStartInterval),
            startRequest.getCronSchedule(),
            lastCompletionResult,
            lastFailure,
            firstExecutionRunId,
            runId, // Test service doesn't support reset. Thus, originalRunId is always the same as
            // runId.
            continuedExecutionRunId);
    this.workflow = StateMachines.newWorkflowStateMachine(data);
    this.workflowTaskStateMachine = StateMachines.newWorkflowTaskStateMachine(store, startRequest);
  }

  /** Based on overrideStartWorkflowExecutionRequest from historyEngine.go */
  private StartWorkflowExecutionRequest overrideStartWorkflowExecutionRequest(
      StartWorkflowExecutionRequest r) {
    StartWorkflowExecutionRequest.Builder request =
        validateStartWorkflowExecutionRequest(r).toBuilder();
    long executionTimeoutMillis = Durations.toMillis(request.getWorkflowExecutionTimeout());
    if (executionTimeoutMillis == 0) {
      executionTimeoutMillis = DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_MILLISECONDS;
    }
    executionTimeoutMillis =
        Math.min(executionTimeoutMillis, DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_MILLISECONDS);
    if (executionTimeoutMillis != Durations.toMillis(request.getWorkflowExecutionTimeout())) {
      request.setWorkflowExecutionTimeout(Durations.fromMillis(executionTimeoutMillis));
    }

    long runTimeoutMillis = Durations.toMillis(request.getWorkflowRunTimeout());
    if (runTimeoutMillis == 0) {
      runTimeoutMillis = DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_MILLISECONDS;
    }
    runTimeoutMillis = Math.min(runTimeoutMillis, DEFAULT_WORKFLOW_EXECUTION_TIMEOUT_MILLISECONDS);
    runTimeoutMillis = Math.min(runTimeoutMillis, executionTimeoutMillis);
    if (runTimeoutMillis != Durations.toMillis(request.getWorkflowRunTimeout())) {
      request.setWorkflowRunTimeout(Durations.fromMillis(runTimeoutMillis));
    }

    long taskTimeoutMillis = Durations.toMillis(request.getWorkflowTaskTimeout());
    if (taskTimeoutMillis == 0) {
      taskTimeoutMillis = DEFAULT_WORKFLOW_TASK_TIMEOUT_MILLISECONDS;
    }
    taskTimeoutMillis = Math.min(taskTimeoutMillis, MAX_WORKFLOW_TASK_TIMEOUT_MILLISECONDS);
    taskTimeoutMillis = Math.min(taskTimeoutMillis, runTimeoutMillis);

    if (taskTimeoutMillis != Durations.toMillis(request.getWorkflowTaskTimeout())) {
      request.setWorkflowTaskTimeout(Durations.fromMillis(taskTimeoutMillis));
    }

    return request.build();
  }

  /** Based on validateStartWorkflowExecutionRequest from historyEngine.go */
  private StartWorkflowExecutionRequest validateStartWorkflowExecutionRequest(
      StartWorkflowExecutionRequest request) {

    if (request.getRequestId().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("Missing request ID.").asRuntimeException();
    }
    if (Durations.toMillis(request.getWorkflowExecutionTimeout()) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Invalid WorkflowExecutionTimeoutSeconds.")
          .asRuntimeException();
    }
    if (Durations.toMillis(request.getWorkflowRunTimeout()) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Invalid WorkflowRunTimeoutSeconds.")
          .asRuntimeException();
    }
    if (Durations.toMillis(request.getWorkflowTaskTimeout()) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Invalid WorkflowTaskTimeoutSeconds.")
          .asRuntimeException();
    }
    if (!request.hasTaskQueue() || request.getTaskQueue().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("Missing TaskQueue.").asRuntimeException();
    }
    if (!request.hasWorkflowType() || request.getWorkflowType().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("Missing WorkflowType.").asRuntimeException();
    }
    if (request.hasRetryPolicy()) {
      validateAndOverrideRetryPolicy(request.getRetryPolicy());
    }
    return request;
  }

  private void update(UpdateProcedure updater) {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    update(false, updater, stackTraceElements[2].getMethodName());
  }

  private void completeWorkflowTaskUpdate(
      UpdateProcedure updater, StickyExecutionAttributes attributes) {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    lock.lock();
    try {
      stickyExecutionAttributes = attributes;
      update(true, updater, stackTraceElements[2].getMethodName());
    } catch (RuntimeException e) {
      stickyExecutionAttributes = null;
      throw e;
    } finally {
      lock.unlock();
    }
  }

  private void update(boolean completeWorkflowTaskUpdate, UpdateProcedure updater, String caller) {
    String callerInfo = "Command Update from " + caller;
    lock.lock();
    LockHandle lockHandle = timerService.lockTimeSkipping(callerInfo);
    try {
      if (isTerminalState()) {
        throw Status.NOT_FOUND.withDescription("Completed workflow").asRuntimeException();
      }
      boolean concurrentWorkflowTask =
          !completeWorkflowTaskUpdate
              && (workflowTaskStateMachine.getState() == StateMachines.State.STARTED);

      RequestContext ctx = new RequestContext(clock, this, nextEventId);
      updater.apply(ctx);

      if (StateUtils.isWorkflowExecutionForcefullyCompleted(workflow.getState())) {
        // if we completed the workflow "externally", not through the result of workflow task
        // (timed out or got a termination request) -
        // we don't buffer the events and don't wait till the finish of the workflow task
        // in-progress even if there is one,
        // but instead we apply them to the history immediately.
        nextEventId = ctx.commitChanges(store);
      } else if (concurrentWorkflowTask) {
        // if there is a concurrent workflow task in progress and the workflow wasn't terminated and
        // considered timed out,
        // we buffer the events and wait till the finish of the workflow task
        workflowTaskStateMachine.getData().bufferedEvents.add(ctx);
        ctx.fireCallbacks(0);
        store.applyTimersAndLocks(ctx);
      } else {
        // if there is no concurrent workflow task in progress - apply events to the history
        nextEventId = ctx.commitChanges(store);
      }

      if (ctx.getException() != null) {
        throw ctx.getException();
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
      case TERMINATED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED;
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
  public void startWorkflowTask(
      PollWorkflowTaskQueueResponse.Builder task, PollWorkflowTaskQueueRequest pollRequest) {
    if (!task.hasQuery()) {
      update(
          ctx -> {
            WorkflowTaskData data = workflowTaskStateMachine.getData();
            long scheduledEventId = data.scheduledEventId;
            workflowTaskStateMachine.action(StateMachines.Action.START, ctx, pollRequest, 0);
            task.setStartedTime(ctx.currentTime());
            ctx.addTimer(
                ProtobufTimeUtils.toJavaDuration(startRequest.getWorkflowTaskTimeout()),
                () -> timeoutWorkflowTask(scheduledEventId),
                "WorkflowTask StartToCloseTimeout");
          });
    }
  }

  @Override
  public void completeWorkflowTask(
      int historySizeFromToken, RespondWorkflowTaskCompletedRequest request) {
    List<Command> commands = request.getCommandsList();
    List<Message> messages = new ArrayList<>(request.getMessagesList());

    completeWorkflowTaskUpdate(
        ctx -> {
          if (ctx.getInitialEventId() != historySizeFromToken + 1) {
            throw Status.NOT_FOUND
                .withDescription(
                    "Expired workflow task: expectedHistorySize="
                        + historySizeFromToken
                        + ","
                        + " actualHistorySize="
                        + ctx.getInitialEventId())
                .asRuntimeException();
          }

          // Workflow completion Command has to be the last in the Workflow Task completion request
          int indexOfCompletionEvent =
              IntStream.range(0, commands.size())
                  .filter(
                      index ->
                          WorkflowExecutionUtils.isWorkflowExecutionCompleteCommand(
                              commands.get(index)))
                  .findFirst()
                  .orElse(-1);
          if (indexOfCompletionEvent >= 0 && indexOfCompletionEvent < commands.size() - 1) {
            throw Status.INVALID_ARGUMENT
                .withDescription(
                    "invalid command sequence: "
                        + commands.stream()
                            .map(Command::getCommandType)
                            .map(ProtoEnumNameUtils::uniqueToSimplifiedName)
                            .collect(Collectors.toList())
                        + ", command "
                        + ProtoEnumNameUtils.uniqueToSimplifiedName(
                            commands.get(indexOfCompletionEvent).getCommandType())
                        + " must be the last command.")
                .asRuntimeException();
          }

          if (unhandledCommand(request) || unhandledMessages(request)) {
            // Fail the workflow task if there are new events or messages and a command tries to
            // complete the
            // workflow
            failWorkflowTaskWithAReason(
                WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_UNHANDLED_COMMAND,
                null,
                ctx,
                request,
                false);
            return;
          }

          for (Command command : commands) {
            CommandVerifier.InvalidCommandResult invalidCommandResult =
                commandVerifier.verifyCommand(ctx, command);
            if (invalidCommandResult != null) {
              failWorkflowTaskWithAReason(
                  invalidCommandResult.getWorkflowTaskFailedCause(),
                  invalidCommandResult.getEventAttributesFailure(),
                  ctx,
                  request,
                  true);
              ctx.setExceptionIfEmpty(invalidCommandResult.getClientException());
              return;
            }
          }

          long workflowTaskCompletedId = ctx.getNextEventId() - 1;
          try {
            workflowTaskStateMachine.action(StateMachines.Action.COMPLETE, ctx, request, 0);
            for (Command command : commands) {
              processCommand(
                  ctx, command, messages, request.getIdentity(), workflowTaskCompletedId);
            }
            // Any messages not processed in processCommand need to be handled after all commands
            for (Message message : messages) {
              processMessage(ctx, message, request.getIdentity(), workflowTaskCompletedId);
            }
            workflowTaskStateMachine.getData().updateRequest.clear();

            for (RequestContext deferredCtx : workflowTaskStateMachine.getData().bufferedEvents) {
              ctx.add(deferredCtx);
            }
            WorkflowTaskData data = this.workflowTaskStateMachine.getData();

            boolean completed =
                workflow.getState() == StateMachines.State.COMPLETED
                    || workflow.getState() == StateMachines.State.FAILED
                    || workflow.getState() == StateMachines.State.CANCELED;
            if (!completed
                && ((ctx.isNeedWorkflowTask()
                        || !workflowTaskStateMachine.getData().bufferedEvents.isEmpty())
                    || !workflowTaskStateMachine.getData().updateRequestBuffer.isEmpty()
                    || request.getForceCreateNewWorkflowTask())) {
              scheduleWorkflowTask(ctx);
            }
            workflowTaskStateMachine.getData().bufferedEvents.clear();
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
                                QueryFailedFailure.getDefaultInstance(),
                                QueryFailedFailure.getDescriptor()));
                    break;
                  case UNRECOGNIZED:
                    throw Status.INVALID_ARGUMENT
                        .withDescription(
                            "UNRECOGNIZED query result type for =" + resultEntry.getKey())
                        .asRuntimeException();
                }
              }
            }
            ctx.onCommit(
                (historySize -> {
                  if (workflowTaskStateMachine.getState() == State.INITIATED) {
                    for (ConsistentQuery query : data.queryBuffer.values()) {
                      workflowTaskStateMachine.action(Action.QUERY, ctx, query, NO_EVENT_ID);
                    }
                  } else {
                    for (ConsistentQuery consistent : data.queryBuffer.values()) {
                      QueryId queryId = new QueryId(executionId, consistent.getKey());
                      PollWorkflowTaskQueueResponse.Builder task =
                          PollWorkflowTaskQueueResponse.newBuilder()
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
                }));
          } finally {
            ctx.unlockTimer("completeWorkflowTask");
          }
        },
        request.hasStickyAttributes() ? request.getStickyAttributes() : null);
  }

  private void failWorkflowTaskWithAReason(
      WorkflowTaskFailedCause failedCause,
      ServerFailure eventAttributesFailure,
      RequestContext ctx,
      RespondWorkflowTaskCompletedRequest request,
      boolean timeoutWorkflowTaskIfRecurringFailure) {
    RespondWorkflowTaskFailedRequest.Builder failedRequestBuilder =
        RespondWorkflowTaskFailedRequest.newBuilder()
            .setCause(failedCause)
            .setIdentity(request.getIdentity());
    if (eventAttributesFailure != null) {
      failedRequestBuilder.setFailure(
          DefaultDataConverter.STANDARD_INSTANCE.exceptionToFailure(eventAttributesFailure));
    }

    processFailWorkflowTask(
        failedRequestBuilder.build(), ctx, timeoutWorkflowTaskIfRecurringFailure);
  }

  private boolean unhandledCommand(RespondWorkflowTaskCompletedRequest request) {
    boolean newEvents = false;
    for (RequestContext ctx2 : workflowTaskStateMachine.getData().bufferedEvents) {
      if (!ctx2.getEvents().isEmpty()) {
        newEvents = true;
        break;
      }
    }
    return (newEvents && hasCompletionCommand(request.getCommandsList()));
  }

  private boolean unhandledMessages(RespondWorkflowTaskCompletedRequest request) {
    return (!workflowTaskStateMachine.getData().updateRequestBuffer.isEmpty()
        && hasCompletionCommand(request.getCommandsList()));
  }

  private boolean hasCompletionCommand(List<Command> commands) {
    for (Command command : commands) {
      if (WorkflowExecutionUtils.isWorkflowExecutionCompleteCommand(command)) {
        return true;
      }
    }
    return false;
  }

  private void processCommand(
      RequestContext ctx,
      Command d,
      List<Message> messages,
      String identity,
      long workflowTaskCompletedId) {
    switch (d.getCommandType()) {
      case COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION:
        processCompleteWorkflowExecution(
            ctx,
            d.getCompleteWorkflowExecutionCommandAttributes(),
            workflowTaskCompletedId,
            identity);
        break;
      case COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION:
        processFailWorkflowExecution(
            ctx, d.getFailWorkflowExecutionCommandAttributes(), workflowTaskCompletedId, identity);
        break;
      case COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION:
        processCancelWorkflowExecution(
            ctx, d.getCancelWorkflowExecutionCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
        processContinueAsNewWorkflowExecution(
            ctx,
            d.getContinueAsNewWorkflowExecutionCommandAttributes(),
            workflowTaskCompletedId,
            identity);
        break;
      case COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK:
        processScheduleActivityTask(
            ctx, d.getScheduleActivityTaskCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK:
        processRequestCancelActivityTask(
            ctx, d.getRequestCancelActivityTaskCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_START_TIMER:
        processStartTimer(ctx, d.getStartTimerCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_CANCEL_TIMER:
        processCancelTimer(ctx, d.getCancelTimerCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION:
        processStartChildWorkflow(
            ctx, d.getStartChildWorkflowExecutionCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION:
        processSignalExternalWorkflowExecution(
            ctx, d.getSignalExternalWorkflowExecutionCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_RECORD_MARKER:
        processRecordMarker(ctx, d.getRecordMarkerCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION:
        processRequestCancelExternalWorkflowExecution(
            ctx,
            d.getRequestCancelExternalWorkflowExecutionCommandAttributes(),
            workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        processUpsertWorkflowSearchAttributes(
            ctx, d.getUpsertWorkflowSearchAttributesCommandAttributes(), workflowTaskCompletedId);
        break;
      case COMMAND_TYPE_PROTOCOL_MESSAGE:
        processProtocolMessageAttributes(
            ctx,
            d.getProtocolMessageCommandAttributes(),
            messages,
            identity,
            workflowTaskCompletedId);
        break;
      default:
        throw Status.INVALID_ARGUMENT
            .withDescription("Unknown command type: " + d.getCommandType() + " for " + d)
            .asRuntimeException();
    }
  }

  private void processMessage(
      RequestContext ctx, Message msg, String identity, long workflowTaskCompletedId) {
    String clazzName = msg.getBody().getTypeUrl().split("/")[1];

    try {
      switch (clazzName) {
        case "temporal.api.update.v1.Acceptance":
          processAcceptanceMessage(
              ctx, msg, msg.getBody().unpack(Acceptance.class), workflowTaskCompletedId);
          break;
        case "temporal.api.update.v1.Rejection":
          processRejectionMessage(
              ctx, msg, msg.getBody().unpack(Rejection.class), workflowTaskCompletedId);
          break;
        case "temporal.api.update.v1.Response":
          processOutcomeMessage(
              ctx, msg, msg.getBody().unpack(Response.class), workflowTaskCompletedId);
          break;
        default:
          throw Status.INVALID_ARGUMENT
              .withDescription(
                  "Unknown message type: " + msg.getProtocolInstanceId() + " for " + msg)
              .asRuntimeException();
      }
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  private void processRequestCancelExternalWorkflowExecution(
      RequestContext ctx,
      RequestCancelExternalWorkflowExecutionCommandAttributes attr,
      long workflowTaskCompletedId) {
    if (externalCancellations.containsKey(attr.getWorkflowId())) {
      // TODO: validate that this matches the service behavior
      throw Status.FAILED_PRECONDITION
          .withDescription("cancellation already requested for workflowId=" + attr.getWorkflowId())
          .asRuntimeException();
    }
    StateMachine<CancelExternalData> cancelStateMachine =
        StateMachines.newCancelExternalStateMachine();
    externalCancellations.put(attr.getWorkflowId(), cancelStateMachine);
    cancelStateMachine.action(StateMachines.Action.INITIATE, ctx, attr, workflowTaskCompletedId);
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
                      ctx.getNamespace(), cancelStateMachine.getData().initiatedEventId, this);
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
          scheduleWorkflowTask(ctx);
          // No need to lock until completion as child workflow might skip
          // time as well
          //          ctx.unlockTimer();
        });
  }

  private void processRecordMarker(
      RequestContext ctx, RecordMarkerCommandAttributes attr, long workflowTaskCompletedId) {
    if (attr.getMarkerName().isEmpty()) {
      throw Status.INVALID_ARGUMENT.withDescription("marker name is required").asRuntimeException();
    }

    MarkerRecordedEventAttributes.Builder marker =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(attr.getMarkerName())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedId)
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
      RequestContext ctx, CancelTimerCommandAttributes d, long workflowTaskCompletedId) {
    String timerId = d.getTimerId();
    StateMachine<TimerData> timer = timers.get(timerId);
    if (timer == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("invalid history builder state for action")
          .asRuntimeException();
    }
    timer.action(StateMachines.Action.CANCEL, ctx, d, workflowTaskCompletedId);
    timers.remove(timerId);
  }

  private void processRequestCancelActivityTask(
      RequestContext ctx,
      RequestCancelActivityTaskCommandAttributes a,
      long workflowTaskCompletedId) {
    long scheduledEventId = a.getScheduledEventId();
    StateMachine<?> activity = activities.get(scheduledEventId);
    if (activity == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("ACTIVITY_UNKNOWN for scheduledEventId=" + scheduledEventId)
          .asRuntimeException();
    }
    State beforeState = activity.getState();
    activity.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, a, workflowTaskCompletedId);
    if (beforeState == StateMachines.State.INITIATED) {
      // request is null here, because it's caused not by a separate cancel request, but by a
      // command
      activity.action(StateMachines.Action.CANCEL, ctx, null, 0);
      activities.remove(scheduledEventId);
      ctx.setNeedWorkflowTask(true);
    }
  }

  private void processScheduleActivityTask(
      RequestContext ctx,
      ScheduleActivityTaskCommandAttributes attributes,
      long workflowTaskCompletedId) {
    attributes = validateScheduleActivityTask(attributes);
    String activityId = attributes.getActivityId();
    Long activityScheduledEventId = activityById.get(activityId);
    if (activityScheduledEventId != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already open activity with " + activityId)
          .asRuntimeException();
    }
    StateMachine<ActivityTaskData> activityStateMachine =
        newActivityStateMachine(store, this.startRequest);
    long activityScheduleId = ctx.getNextEventId();
    activities.put(activityScheduleId, activityStateMachine);
    activityById.put(activityId, activityScheduleId);
    activityStateMachine.action(
        StateMachines.Action.INITIATE, ctx, attributes, workflowTaskCompletedId);
    ActivityTaskScheduledEventAttributes scheduledEvent =
        activityStateMachine.getData().scheduledEvent;
    int attempt = activityStateMachine.getData().getAttempt();
    ctx.addTimer(
        ProtobufTimeUtils.toJavaDuration(scheduledEvent.getScheduleToCloseTimeout()),
        () ->
            timeoutActivity(
                activityScheduleId, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, attempt),
        "Activity ScheduleToCloseTimeout");
    ctx.addTimer(
        ProtobufTimeUtils.toJavaDuration(scheduledEvent.getScheduleToStartTimeout()),
        () ->
            timeoutActivity(
                activityScheduleId, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START, attempt),
        "Activity ScheduleToStartTimeout");
    ctx.lockTimer("processScheduleActivityTask");
  }

  /**
   * The logic is copied from history service implementation of validateActivityScheduleAttributes
   * function.
   */
  private ScheduleActivityTaskCommandAttributes validateScheduleActivityTask(
      ScheduleActivityTaskCommandAttributes a) {
    ScheduleActivityTaskCommandAttributes.Builder result = a.toBuilder();
    if (!a.hasTaskQueue() || a.getTaskQueue().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("TaskQueue is not set on workflow task")
          .asRuntimeException();
    }
    if (a.getActivityId().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ActivityId is not set on workflow task")
          .asRuntimeException();
    }
    if (!a.hasActivityType() || a.getActivityType().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ActivityType is not set on workflow task")
          .asRuntimeException();
    }
    // Only attempt to deduce and fill in unspecified timeouts only when all timeouts are
    // zero or greater
    if (Durations.compare(a.getScheduleToCloseTimeout(), Durations.ZERO) < 0
        || Durations.compare(a.getScheduleToStartTimeout(), Durations.ZERO) < 0
        || Durations.compare(a.getStartToCloseTimeout(), Durations.ZERO) < 0
        || Durations.compare(a.getHeartbeatTimeout(), Durations.ZERO) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("A valid timeout may not be negative.")
          .asRuntimeException();
    }
    com.google.protobuf.Duration workflowRunTimeout = this.startRequest.getWorkflowRunTimeout();
    boolean validScheduleToClose =
        Durations.compare(a.getScheduleToCloseTimeout(), Durations.ZERO) > 0;
    boolean validScheduleToStart =
        Durations.compare(a.getScheduleToStartTimeout(), Durations.ZERO) > 0;
    boolean validStartToClose = Durations.compare(a.getStartToCloseTimeout(), Durations.ZERO) > 0;

    if (validScheduleToClose) {
      if (validScheduleToStart) {
        result.setScheduleToStartTimeout(
            Durations.fromMillis(
                Math.min(
                    Durations.toMillis(a.getScheduleToStartTimeout()),
                    Durations.toMillis(a.getScheduleToCloseTimeout()))));
      } else {
        result.setScheduleToStartTimeout(a.getScheduleToCloseTimeout());
      }
      if (validStartToClose) {
        result.setStartToCloseTimeout(
            Durations.fromMillis(
                Math.min(
                    Durations.toMillis(a.getStartToCloseTimeout()),
                    Durations.toMillis(a.getScheduleToCloseTimeout()))));
      } else {
        result.setStartToCloseTimeout(a.getScheduleToCloseTimeout());
      }
    } else if (validStartToClose) {
      // We are in !validScheduleToClose due to the first if above
      result.setScheduleToCloseTimeout(workflowRunTimeout);
      if (!validScheduleToStart) {
        result.setScheduleToStartTimeout(workflowRunTimeout);
      }
    } else {
      // Deduction failed as there's not enough information to fill in missing timeouts.
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "A valid StartToClose or ScheduleToCloseTimeout is not set on workflow task.")
          .asRuntimeException();
    }
    // ensure activity timeout never larger than workflow run timeout
    if (Durations.compare(workflowRunTimeout, Durations.ZERO) > 0) {
      if (Durations.compare(a.getScheduleToCloseTimeout(), workflowRunTimeout) > 0) {
        result.setScheduleToCloseTimeout(workflowRunTimeout);
      }
      if (Durations.compare(a.getScheduleToStartTimeout(), workflowRunTimeout) > 0) {
        result.setScheduleToStartTimeout(workflowRunTimeout);
      }
      if (Durations.compare(a.getStartToCloseTimeout(), workflowRunTimeout) > 0) {
        result.setStartToCloseTimeout(workflowRunTimeout);
      }
      if (Durations.compare(a.getHeartbeatTimeout(), workflowRunTimeout) > 0) {
        result.setHeartbeatTimeout(workflowRunTimeout);
      }
    }

    // if scheduleToClose is set, heartbeat timeout should not be larger than scheduleToClose
    if (validScheduleToClose) {
      if (Durations.compare(a.getHeartbeatTimeout(), a.getScheduleToCloseTimeout()) > 0) {
        result.setHeartbeatTimeout(a.getScheduleToCloseTimeout());
      }
    }

    return result.build();
  }

  private void processStartChildWorkflow(
      RequestContext ctx,
      StartChildWorkflowExecutionCommandAttributes a,
      long workflowTaskCompletedId) {
    a = validateStartChildExecutionAttributes(a);
    StateMachine<ChildWorkflowData> child = StateMachines.newChildWorkflowStateMachine(service);
    childWorkflows.put(ctx.getNextEventId(), child);
    child.action(StateMachines.Action.INITIATE, ctx, a, workflowTaskCompletedId);
    ctx.lockTimer("processStartChildWorkflow");
  }

  /** Clone of the validateStartChildExecutionAttributes from historyEngine.go */
  private StartChildWorkflowExecutionCommandAttributes validateStartChildExecutionAttributes(
      StartChildWorkflowExecutionCommandAttributes a) {
    if (a == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "StartChildWorkflowExecutionCommandAttributes is not set on workflow task")
          .asRuntimeException();
    }

    if (a.getWorkflowId().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Required field WorkflowId is not set on workflow task")
          .asRuntimeException();
    }

    if (!a.hasWorkflowType() || a.getWorkflowType().getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Required field WorkflowType is not set on workflow task")
          .asRuntimeException();
    }

    StartChildWorkflowExecutionCommandAttributes.Builder ab = a.toBuilder();
    if (a.hasRetryPolicy()) {
      ab.setRetryPolicy(validateAndOverrideRetryPolicy(a.getRetryPolicy()));
    }

    // Inherit task queue from parent workflow execution if not provided on workflow task
    if (!ab.hasTaskQueue()) {
      ab.setTaskQueue(startRequest.getTaskQueue());
    }

    // Inherit workflow task timeout from parent workflow execution if not provided on workflow task
    if (Durations.compare(a.getWorkflowTaskTimeout(), Durations.ZERO) <= 0) {
      ab.setWorkflowTaskTimeout(startRequest.getWorkflowTaskTimeout());
    }

    return ab.build();
  }

  private void processSignalExternalWorkflowExecution(
      RequestContext ctx,
      SignalExternalWorkflowExecutionCommandAttributes a,
      long workflowTaskCompletedId) {
    String signalId = UUID.randomUUID().toString();
    StateMachine<SignalExternalData> signalStateMachine =
        StateMachines.newSignalExternalStateMachine();
    externalSignals.put(signalId, signalStateMachine);
    signalStateMachine.action(StateMachines.Action.INITIATE, ctx, a, workflowTaskCompletedId);
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              try {
                service.signalExternalWorkflowExecution(signalId, a, this);
              } catch (Exception e) {
                log.error("Failure signalling an external workflow execution", e);
              }
            });
    ctx.lockTimer("processSignalExternalWorkflowExecution");
  }

  @Override
  public void completeSignalExternalWorkflowExecution(String signalId, String runId) {
    update(
        ctx -> {
          StateMachine<SignalExternalData> signal = getSignal(signalId);
          signal.action(Action.COMPLETE, ctx, runId, 0);
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("completeSignalExternalWorkflowExecution");
        });
  }

  @Override
  public void failSignalExternalWorkflowExecution(
      String signalId, SignalExternalWorkflowExecutionFailedCause cause) {
    update(
        ctx -> {
          StateMachine<SignalExternalData> signal = getSignal(signalId);
          signal.action(Action.FAIL, ctx, cause, 0);
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("failSignalExternalWorkflowExecution");
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

  // TODO: insert a single workflow task failure into the history
  @Override
  public void failWorkflowTask(RespondWorkflowTaskFailedRequest request) {
    completeWorkflowTaskUpdate(
        ctx -> processFailWorkflowTask(request, ctx, false),
        null); // reset sticky attributes to null
  }

  private void processFailWorkflowTask(
      RespondWorkflowTaskFailedRequest request,
      RequestContext ctx,
      boolean timeoutWorkflowTaskIfRecurringFailure) {
    WorkflowTaskData data = workflowTaskStateMachine.getData();
    if (timeoutWorkflowTaskIfRecurringFailure && data.attempt >= 2) {
      // server drops failures after the second attempt and let the workflow task timeout
      return;
    }
    workflowTaskStateMachine.action(Action.FAIL, ctx, request, 0);
    for (RequestContext deferredCtx : workflowTaskStateMachine.getData().bufferedEvents) {
      ctx.add(deferredCtx);
    }
    workflowTaskStateMachine.getData().bufferedEvents.clear();
    scheduleWorkflowTask(ctx);
    ctx.unlockTimer("failWorkflowTask"); // Unlock timer associated with the workflow task
  }

  // TODO: insert a single  workflow task timeout into the history
  private void timeoutWorkflowTask(long scheduledEventId) {
    StickyExecutionAttributes previousStickySettings = this.stickyExecutionAttributes;
    try {
      completeWorkflowTaskUpdate(
          ctx -> {
            if (workflowTaskStateMachine == null
                || workflowTaskStateMachine.getData().scheduledEventId != scheduledEventId
                || workflowTaskStateMachine.getState() == State.NONE) {
              // timeout for a previous workflow task
              stickyExecutionAttributes = previousStickySettings; // rollout sticky options
              return;
            }
            workflowTaskStateMachine
                .getData()
                .queryBuffer
                .entrySet()
                .removeIf(queryEntry -> queryEntry.getValue().getResult().isCancelled());
            workflowTaskStateMachine.action(
                StateMachines.Action.TIME_OUT, ctx, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, 0);
            scheduleWorkflowTask(ctx);
            ctx.unlockTimer(
                "timeoutWorkflowTask"); // Unlock timer associated with the workflow task
          },
          null); // reset sticky attributes to null
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
        // Cannot fail to timer threads
        log.error(
            "Failure trying to timeout a workflow task scheduledEventId=" + scheduledEventId, e);
      }
      // Expected as timers are not removed
    } catch (Exception e) {
      // Cannot fail to timer threads
      log.error(
          "Failure trying to timeout a workflow task scheduledEventId=" + scheduledEventId, e);
    }
  }

  @Override
  public void childWorkflowStarted(ChildWorkflowExecutionStartedEventAttributes a) {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(StateMachines.Action.START, ctx, a, 0);
          scheduleWorkflowTask(ctx);
          // No need to lock until completion as child workflow might skip
          // time as well
          ctx.unlockTimer("childWorkflowStarted");
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
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("childWorkflowFailed");
        });
  }

  @Override
  public void childWorkflowTimedOut(
      String activityId, ChildWorkflowExecutionTimedOutEventAttributes a) {
    update(
        ctx -> {
          StateMachine<ChildWorkflowData> child = getChildWorkflow(a.getInitiatedEventId());
          child.action(Action.TIME_OUT, ctx, a.getRetryState(), 0);
          childWorkflows.remove(a.getInitiatedEventId());
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("childWorkflowTimedOut");
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
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("failStartChildWorkflow");
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
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("childWorkflowCompleted");
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
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("childWorkflowCanceled");
        });
  }

  private void processStartTimer(
      RequestContext ctx, StartTimerCommandAttributes a, long workflowTaskCompletedId) {
    String timerId = a.getTimerId();
    StateMachine<TimerData> timer = timers.get(timerId);

    if (timer != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already open timer with " + timerId)
          .asRuntimeException();
    }
    timer = StateMachines.newTimerStateMachine();
    timers.put(timerId, timer);
    timer.action(StateMachines.Action.START, ctx, a, workflowTaskCompletedId);
    ctx.addTimer(
        ProtobufTimeUtils.toJavaDuration(a.getStartToFireTimeout()),
        () -> fireTimer(timerId),
        "fire timer");
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
          return; // canceled already
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
            scheduleWorkflowTask(ctx);
          });
    } catch (Throwable e) {
      // Cannot fail to timer threads
      log.error("Failure firing a timer", e);
    }
  }

  private void processFailWorkflowExecution(
      RequestContext ctx,
      FailWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedId,
      String identity) {

    // This should probably follow the retry logic from
    // https://github.com/temporalio/temporal/blob/master/service/history/retry.go#L95
    Failure failure = d.getFailure();
    WorkflowData data = workflow.getData();

    if (data.retryState.isPresent()) {

      TestServiceRetryState rs = data.retryState.get();
      Optional<String> failureType;
      TestServiceRetryState.BackoffInterval backoffInterval;

      if (failure.hasApplicationFailureInfo()) {
        // Application failure
        ApplicationFailureInfo failureInfo = failure.getApplicationFailureInfo();
        if (failureInfo.getNonRetryable()) {
          backoffInterval =
              new TestServiceRetryState.BackoffInterval(
                  RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE);
        } else {
          failureType = Optional.of(failureInfo.getType());
          backoffInterval = rs.getBackoffIntervalInSeconds(failureType, store.currentTime());
        }
      } else if (failure.hasTerminatedFailureInfo()
          || failure.hasCanceledFailureInfo()
          || (failure.hasServerFailureInfo() && failure.getServerFailureInfo().getNonRetryable())) {
        // Indicate that the failure is not retryable.
        backoffInterval =
            new TestServiceRetryState.BackoffInterval(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE);
      } else {
        // The failure may be retryable. (E.g. ActivityFailure)
        backoffInterval = rs.getBackoffIntervalInSeconds(Optional.empty(), store.currentTime());
      }

      if (backoffInterval.getRetryState() == RetryState.RETRY_STATE_IN_PROGRESS) {
        ContinueAsNewWorkflowExecutionCommandAttributes.Builder continueAsNewAttr =
            ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder()
                .setInput(startRequest.getInput())
                .setWorkflowType(startRequest.getWorkflowType())
                .setWorkflowRunTimeout(startRequest.getWorkflowRunTimeout())
                .setWorkflowTaskTimeout(startRequest.getWorkflowTaskTimeout())
                .setBackoffStartInterval(
                    ProtobufTimeUtils.toProtoDuration(backoffInterval.getInterval()));
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
            Action.CONTINUE_AS_NEW, ctx, continueAsNewAttr.build(), workflowTaskCompletedId);
        workflowTaskStateMachine.getData().workflowCompleted = true;
        HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
        WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewEventAttributes =
            event.getWorkflowExecutionContinuedAsNewEventAttributes();

        Optional<TestServiceRetryState> continuedRetryState =
            Optional.of(rs.getNextAttempt(Optional.of(failure)));
        service.continueAsNew(
            startRequest,
            continuedAsNewEventAttributes,
            continuedRetryState,
            identity,
            getExecutionId(),
            workflow.getData().firstExecutionRunId,
            parent,
            parentChildInitiatedEventId);
        return;
      }
    }

    if (!Strings.isNullOrEmpty(data.cronSchedule)) {
      startNewCronRun(
          ctx,
          workflowTaskCompletedId,
          identity,
          data,
          data.lastCompletionResult,
          Optional.of(failure));
      return;
    }

    workflow.action(StateMachines.Action.FAIL, ctx, d, workflowTaskCompletedId);
    workflowTaskStateMachine.getData().workflowCompleted = true;
    if (parent.isPresent()) {
      ctx.lockTimer("processFailWorkflowExecution notify parent"); // unlocked by the parent
      ChildWorkflowExecutionFailedEventAttributes a =
          ChildWorkflowExecutionFailedEventAttributes.newBuilder()
              .setInitiatedEventId(parentChildInitiatedEventId.getAsLong())
              .setFailure(failure)
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
      CompleteWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedId,
      String identity) {
    WorkflowData data = workflow.getData();
    if (!Strings.isNullOrEmpty(data.cronSchedule)) {
      startNewCronRun(
          ctx, workflowTaskCompletedId, identity, data, d.getResult(), Optional.empty());
      return;
    }

    workflow.action(StateMachines.Action.COMPLETE, ctx, d, workflowTaskCompletedId);
    workflowTaskStateMachine.getData().workflowCompleted = true;
    // cancel run timer to avoid time skipping to the workflow run timeout which defaults to 10
    // years
    workflow.getData().runTimerCancellationHandle.apply();
    if (parent.isPresent()) {
      ctx.lockTimer("processCompleteWorkflowExecution notify parent"); // unlocked by the parent
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
      long workflowTaskCompletedId,
      String identity,
      WorkflowData data,
      Payloads lastCompletionResult,
      Optional<Failure> lastFailure) {
    Objects.requireNonNull(lastFailure);

    Duration backoffInterval = getBackoffInterval(data.cronSchedule, store.currentTime());
    ContinueAsNewWorkflowExecutionCommandAttributes.Builder builder =
        ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder()
            .setInput(startRequest.getInput())
            .setWorkflowType(startRequest.getWorkflowType())
            .setWorkflowRunTimeout(startRequest.getWorkflowRunTimeout())
            .setWorkflowTaskTimeout(startRequest.getWorkflowTaskTimeout())
            .setTaskQueue(startRequest.getTaskQueue())
            .setBackoffStartInterval(ProtobufTimeUtils.toProtoDuration(backoffInterval))
            .setRetryPolicy(startRequest.getRetryPolicy())
            .setLastCompletionResult(lastCompletionResult);
    lastFailure.ifPresent(builder::setFailure);
    ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewAttr = builder.build();
    workflow.action(Action.CONTINUE_AS_NEW, ctx, continueAsNewAttr, workflowTaskCompletedId);
    workflowTaskStateMachine.getData().workflowCompleted = true;
    HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
    WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewEventAttributes =
        event.getWorkflowExecutionContinuedAsNewEventAttributes();
    service.continueAsNew(
        startRequest,
        continuedAsNewEventAttributes,
        Optional.empty(),
        identity,
        getExecutionId(),
        workflow.getData().firstExecutionRunId,
        parent,
        parentChildInitiatedEventId);
  }

  private void processCancelWorkflowExecution(
      RequestContext ctx,
      CancelWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedId) {
    workflow.action(StateMachines.Action.CANCEL, ctx, d, workflowTaskCompletedId);
    workflowTaskStateMachine.getData().workflowCompleted = true;
    if (parent.isPresent()) {
      ctx.lockTimer("processCancelWorkflowExecution notify parent"); // unlocked by the parent
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
      ContinueAsNewWorkflowExecutionCommandAttributes d,
      long workflowTaskCompletedId,
      String identity) {
    workflow.action(Action.CONTINUE_AS_NEW, ctx, d, workflowTaskCompletedId);
    workflowTaskStateMachine.getData().workflowCompleted = true;
    HistoryEvent event = ctx.getEvents().get(ctx.getEvents().size() - 1);
    service.continueAsNew(
        startRequest,
        event.getWorkflowExecutionContinuedAsNewEventAttributes(),
        workflow.getData().retryState,
        identity,
        getExecutionId(),
        workflow.getData().firstExecutionRunId,
        parent,
        parentChildInitiatedEventId);
  }

  private WorkflowTaskFailedCause processUpsertWorkflowSearchAttributes(
      RequestContext ctx,
      UpsertWorkflowSearchAttributesCommandAttributes attr,
      long workflowTaskCompletedId) {
    visibilityStore.upsertSearchAttributesForExecution(
        ctx.getExecutionId(), attr.getSearchAttributes());

    UpsertWorkflowSearchAttributesEventAttributes.Builder upsertEventAttr =
        UpsertWorkflowSearchAttributesEventAttributes.newBuilder()
            .setSearchAttributes(attr.getSearchAttributes())
            .setWorkflowTaskCompletedEventId(workflowTaskCompletedId);
    HistoryEvent event =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES)
            .setUpsertWorkflowSearchAttributesEventAttributes(upsertEventAttr)
            .build();
    ctx.addEvent(event);
    return null;
  }

  /**
   * processProtocolMessageAttributes handles protocol messages, it is expected to look up the
   * {@code Message} in the given {@code List<Message>} process that message and remove that {@code
   * Message} from the list.
   */
  private WorkflowTaskFailedCause processProtocolMessageAttributes(
      RequestContext ctx,
      ProtocolMessageCommandAttributes attr,
      List<Message> messages,
      String identity,
      long workflowTaskCompletedId) {
    Message orderedMsg =
        messages.stream()
            .filter(msg -> msg.getId().equals(attr.getMessageId()))
            .findFirst()
            .map(
                msg -> {
                  messages.remove(msg);
                  return msg;
                })
            .get();
    processMessage(ctx, orderedMsg, identity, workflowTaskCompletedId);
    return null;
  }

  private void processAcceptanceMessage(
      RequestContext ctx, Message msg, Acceptance acceptance, long workflowTaskCompletedId) {
    String protocolInstanceId = msg.getProtocolInstanceId();
    StateMachine<UpdateWorkflowExecutionData> update = updates.get(protocolInstanceId);

    if (update != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already accepted update with Id " + protocolInstanceId)
          .asRuntimeException();
    }
    UpdateWorkflowExecution u =
        workflowTaskStateMachine.getData().updateRequest.get(protocolInstanceId);

    update =
        StateMachines.newUpdateWorkflowExecution(
            protocolInstanceId, u.getRequest().getRequest(), u.getAcceptance(), u.getCompletion());
    updates.put(protocolInstanceId, update);
    update.action(StateMachines.Action.START, ctx, msg, workflowTaskCompletedId);
  }

  private void processRejectionMessage(
      RequestContext ctx, Message msg, Rejection rejection, long workflowTaskCompletedId) {
    String protocolInstanceId = msg.getProtocolInstanceId();
    StateMachine<UpdateWorkflowExecutionData> update = updates.get(protocolInstanceId);

    if (update != null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Already accepted update with Id " + protocolInstanceId)
          .asRuntimeException();
    }
    UpdateWorkflowExecution u =
        workflowTaskStateMachine.getData().updateRequest.get(msg.getProtocolInstanceId());
    // If an update validation fail, do not write to history and do not store the update.
    UpdateWorkflowExecutionResponse response =
        UpdateWorkflowExecutionResponse.newBuilder()
            .setUpdateRef(
                UpdateRef.newBuilder()
                    .setUpdateId(rejection.getRejectedRequest().getMeta().getUpdateId())
                    .setWorkflowExecution(ctx.getExecution()))
            .setOutcome(Outcome.newBuilder().setFailure(rejection.getFailure()).build())
            .build();
    u.getAcceptance().complete(response);
  }

  private void processOutcomeMessage(
      RequestContext ctx, Message msg, Response response, long workflowTaskCompletedId) {
    String protocolInstanceId = msg.getProtocolInstanceId();
    StateMachine<UpdateWorkflowExecutionData> update = updates.get(protocolInstanceId);

    if (update == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("No update with Id " + protocolInstanceId)
          .asRuntimeException();
    }
    update.action(Action.COMPLETE, ctx, msg, workflowTaskCompletedId);
  }

  @Override
  @Nullable
  public PollWorkflowTaskQueueResponse startWorkflow(
      boolean continuedAsNew,
      @Nullable SignalWorkflowExecutionRequest signalWithStartSignal,
      @Nullable PollWorkflowTaskQueueRequest eagerWorkflowTaskDispatchPollRequest) {
    AtomicReference<TestWorkflowStore.WorkflowTask> eagerWorkflowTask = new AtomicReference<>();
    try {
      update(
          ctx -> {
            visibilityStore.upsertSearchAttributesForExecution(
                ctx.getExecutionId(), startRequest.getSearchAttributes());
            workflow.action(StateMachines.Action.START, ctx, startRequest, 0);
            if (signalWithStartSignal != null) {
              addExecutionSignaledEvent(ctx, signalWithStartSignal);
            }
            Duration backoffStartInterval =
                ProtobufTimeUtils.toJavaDuration(workflow.getData().backoffStartInterval);
            if (backoffStartInterval.compareTo(Duration.ZERO) > 0) {
              // no eager dispatch if backoff is set
              ctx.addTimer(
                  backoffStartInterval,
                  () -> {
                    try {
                      update(this::scheduleWorkflowTask);
                    } catch (StatusRuntimeException e) {
                      // NOT_FOUND is expected as timers are not removed
                      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                        log.error("Failure trying to add task for an delayed workflow retry", e);
                      }
                    } catch (Throwable e) {
                      log.error("Failure trying to add task for an delayed workflow retry", e);
                    }
                  },
                  "delayedFirstWorkflowTask");
            } else {
              scheduleWorkflowTask(ctx);
              if (eagerWorkflowTaskDispatchPollRequest != null) {
                // we don't want this workflow task to escape and to be put on a matching task
                // queue,
                // we have the poll request already waiting for it
                eagerWorkflowTask.set(ctx.resetWorkflowTaskForMatching());
              }
            }

            Duration runTimeout =
                ProtobufTimeUtils.toJavaDuration(startRequest.getWorkflowRunTimeout());
            if (backoffStartInterval.compareTo(Duration.ZERO) > 0) {
              runTimeout = runTimeout.plus(backoffStartInterval);
            }
            workflow.getData().runTimerCancellationHandle =
                ctx.addTimer(runTimeout, this::timeoutWorkflow, "workflow execution timeout");
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

      // notifying the parent state machine in the same transaction and thread, otherwise the parent
      // may see
      // completion before start if it's done asynchronously.
      try {
        parent.get().childWorkflowStarted(a);
      } catch (StatusRuntimeException e) {
        // NOT_FOUND is expected as the parent might just close by now.
        if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
          log.error("Failure reporting child completion", e);
        }
      } catch (Exception e) {
        log.error("Failure trying to add task for an delayed workflow retry", e);
      }
    }

    if (eagerWorkflowTask.get() != null) {
      PollWorkflowTaskQueueResponse.Builder task = eagerWorkflowTask.get().getTask();
      startWorkflowTask(task, eagerWorkflowTaskDispatchPollRequest);
      return task.build();
    } else {
      return null;
    }
  }

  private void scheduleWorkflowTask(RequestContext ctx) {
    State beforeState = workflowTaskStateMachine.getState();
    workflowTaskStateMachine.action(StateMachines.Action.INITIATE, ctx, startRequest, 0);
    // Do not lock if there is an outstanding workflow task.
    if (beforeState == State.NONE && workflowTaskStateMachine.getState() == State.INITIATED) {
      ctx.lockTimer("scheduleWorkflowTask");
    }
  }

  @Override
  public void startActivityTask(
      PollActivityTaskQueueResponseOrBuilder task, PollActivityTaskQueueRequest pollRequest) {
    update(
        ctx -> {
          String activityId = task.getActivityId();
          StateMachine<ActivityTaskData> activityStateMachine = getPendingActivityById(activityId);
          activityStateMachine.action(StateMachines.Action.START, ctx, pollRequest, 0);
          ActivityTaskData data = activityStateMachine.getData();
          data.identity = pollRequest.getIdentity();
          Duration startToCloseTimeout =
              ProtobufTimeUtils.toJavaDuration(data.scheduledEvent.getStartToCloseTimeout());
          Duration heartbeatTimeout =
              ProtobufTimeUtils.toJavaDuration(data.scheduledEvent.getHeartbeatTimeout());
          long scheduledEventId = activityStateMachine.getData().scheduledEventId;
          if (startToCloseTimeout.compareTo(Duration.ZERO) > 0) {
            int attempt = data.getAttempt();
            ctx.addTimer(
                startToCloseTimeout,
                () -> {
                  timeoutActivity(
                      scheduledEventId, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE, attempt);
                },
                "Activity StartToCloseTimeout");
          }
          updateHeartbeatTimer(
              ctx, scheduledEventId, activityStateMachine, startToCloseTimeout, heartbeatTimeout);
        });
  }

  @Override
  public boolean isTerminalState() {
    State workflowState = workflow.getState();
    return isTerminalState(workflowState);
  }

  private void updateHeartbeatTimer(
      RequestContext ctx,
      long activityId,
      StateMachine<ActivityTaskData> activity,
      Duration startToCloseTimeout,
      Duration heartbeatTimeout) {
    if (heartbeatTimeout.compareTo(Duration.ZERO) > 0
        && heartbeatTimeout.compareTo(startToCloseTimeout) < 0) {
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
          StateMachine<ActivityTaskData> activity =
              getPendingActivityByScheduledEventId(scheduledEventId);
          throwIfTaskTokenDoesntMatch(request.getTaskToken(), activity.getData());
          activity.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          removeActivity(scheduledEventId);
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("completeActivityTask");
        });
  }

  @Override
  public void completeActivityTaskById(
      String activityId, RespondActivityTaskCompletedByIdRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getPendingActivityById(activityId);
          activity.action(StateMachines.Action.COMPLETE, ctx, request, 0);
          removeActivity(activity.getData().scheduledEventId);
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("completeActivityTaskById");
        });
  }

  @Override
  public void failActivityTask(long scheduledEventId, RespondActivityTaskFailedRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity =
              getPendingActivityByScheduledEventId(scheduledEventId);
          throwIfTaskTokenDoesntMatch(request.getTaskToken(), activity.getData());
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          if (isTerminalState(activity.getState())) {
            removeActivity(scheduledEventId);
            scheduleWorkflowTask(ctx);
          } else {
            addActivityRetryTimer(ctx, activity);
          }
          // Allow time skipping when waiting for retry
          ctx.unlockTimer("failActivityTask");
        });
  }

  private void addActivityRetryTimer(RequestContext ctx, StateMachine<ActivityTaskData> activity) {
    ActivityTaskData data = activity.getData();
    int attempt = data.getAttempt();
    ctx.addTimer(
        ProtobufTimeUtils.toJavaDuration(data.nextBackoffInterval),
        () -> {
          // Timers are not removed, so skip if it is not for this attempt.
          if (activity.getState() != State.INITIATED && data.getAttempt() != attempt) {
            return;
          }
          LockHandle lockHandle =
              timerService.lockTimeSkipping(
                  "activityRetryTimer " + activity.getData().scheduledEvent.getActivityId());
          boolean unlockTimer = false;
          try {
            // TODO this lock is getting releases somewhere on the activity completion.
            // We should rework it on passing the lockHandle downstream and using it for the release
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
              lockHandle.unlock(
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
          StateMachine<ActivityTaskData> activity = getPendingActivityById(activityId);
          throwIfActivityNotInFlightState(activity.getState());
          activity.action(StateMachines.Action.FAIL, ctx, request, 0);
          if (isTerminalState(activity.getState())) {
            removeActivity(activity.getData().scheduledEventId);
            scheduleWorkflowTask(ctx);
          } else {
            addActivityRetryTimer(ctx, activity);
          }
          ctx.unlockTimer("failActivityTaskById");
        });
  }

  @Override
  public void cancelActivityTask(
      long scheduledEventId, RespondActivityTaskCanceledRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity =
              getPendingActivityByScheduledEventId(scheduledEventId);
          throwIfTaskTokenDoesntMatch(request.getTaskToken(), activity.getData());
          throwIfActivityNotInFlightState(activity.getState());
          activity.action(StateMachines.Action.CANCEL, ctx, request, 0);
          removeActivity(scheduledEventId);
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("cancelActivityTask");
        });
  }

  @Override
  public void cancelActivityTaskById(
      String activityId, RespondActivityTaskCanceledByIdRequest request) {
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity = getPendingActivityById(activityId);
          throwIfActivityNotInFlightState(activity.getState());
          activity.action(StateMachines.Action.CANCEL, ctx, request, 0);
          removeActivity(activity.getData().scheduledEventId);
          scheduleWorkflowTask(ctx);
          ctx.unlockTimer("cancelActivityTaskById");
        });
  }

  @Override
  public boolean heartbeatActivityTask(long scheduledEventId, Payloads details) {
    AtomicBoolean result = new AtomicBoolean();
    update(
        ctx -> {
          StateMachine<ActivityTaskData> activity =
              getPendingActivityByScheduledEventId(scheduledEventId);
          throwIfActivityNotInFlightState(activity.getState());
          activity.action(StateMachines.Action.UPDATE, ctx, details, 0);
          if (activity.getState() == StateMachines.State.CANCELLATION_REQUESTED) {
            result.set(true);
          }
          ActivityTaskData data = activity.getData();
          data.lastHeartbeatTime = clock.getAsLong();
          Duration startToCloseTimeout =
              ProtobufTimeUtils.toJavaDuration(data.scheduledEvent.getStartToCloseTimeout());
          Duration heartbeatTimeout =
              ProtobufTimeUtils.toJavaDuration(data.scheduledEvent.getHeartbeatTimeout());
          updateHeartbeatTimer(
              ctx, scheduledEventId, activity, startToCloseTimeout, heartbeatTimeout);
        });
    return result.get();
  }

  @Override
  public boolean heartbeatActivityTaskById(String id, Payloads details, String identity) {
    StateMachine<ActivityTaskData> activity = getPendingActivityById(id);
    return heartbeatActivityTask(activity.getData().scheduledEventId, details);
  }

  private void timeoutActivity(long scheduledEventId, TimeoutType timeoutType, int timeoutAttempt) {
    boolean unlockTimer = true;
    try {
      update(
          ctx -> {
            StateMachine<ActivityTaskData> activity =
                getPendingActivityByScheduledEventId(scheduledEventId);

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
              // Deal with timers which are never canceled
              long heartbeatTimeout =
                  Durations.toMillis(activity.getData().scheduledEvent.getHeartbeatTimeout());
              if (clock.getAsLong() - activity.getData().lastHeartbeatTime < heartbeatTimeout) {
                throw Status.NOT_FOUND.withDescription("Timer fired earlier").asRuntimeException();
              }
            }
            activity.action(StateMachines.Action.TIME_OUT, ctx, timeoutType, 0);
            if (isTerminalState(activity.getState())) {
              removeActivity(scheduledEventId);
              scheduleWorkflowTask(ctx);
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
        timerService.unlockTimeSkipping("timeoutActivity: " + scheduledEventId);
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
            workflow.action(StateMachines.Action.TIME_OUT, ctx, RetryState.RETRY_STATE_TIMEOUT, 0);
            workflowTaskStateMachine.getData().workflowCompleted = true;
            if (parent.isPresent()) {
              ctx.lockTimer("timeoutWorkflow notify parent"); // unlocked by the parent
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
              .setRetryState(RetryState.RETRY_STATE_TIMEOUT) // TODO(maxim): Real status
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
          scheduleWorkflowTask(ctx);
        });
  }

  @Override
  public void signalFromWorkflow(SignalExternalWorkflowExecutionCommandAttributes a) {
    update(
        ctx -> {
          addExecutionSignaledByExternalEvent(ctx, a);
          scheduleWorkflowTask(ctx);
        });
  }

  @Override
  public UpdateWorkflowExecutionResponse updateWorkflowExecution(
      UpdateWorkflowExecutionRequest request, Deadline deadline) {
    try {
      UpdateHandle updateHandle = getOrCreateUpdate(request);
      switch (request.getWaitPolicy().getLifecycleStage()) {
        case UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED:
          UpdateWorkflowExecutionResponse acceptResponse =
              updateHandle
                  .getAcceptance()
                  .get(
                      deadline != null
                          ? deadline.timeRemaining(TimeUnit.MILLISECONDS)
                          : Long.MAX_VALUE,
                      TimeUnit.MILLISECONDS);
          if (acceptResponse.getOutcome().hasFailure()) {
            return acceptResponse;
          }
          return updateHandle
              .getCompletion()
              .get(
                  deadline != null ? deadline.timeRemaining(TimeUnit.MILLISECONDS) : Long.MAX_VALUE,
                  TimeUnit.MILLISECONDS);
        case UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED:
          return updateHandle
              .getAcceptance()
              .get(
                  deadline != null ? deadline.timeRemaining(TimeUnit.MILLISECONDS) : Long.MAX_VALUE,
                  TimeUnit.MILLISECONDS);
        default:
          throw Status.INTERNAL
              .withDescription(
                  "TestServer does not support this wait policy: "
                      + request.getWaitPolicy().getLifecycleStage())
              .asRuntimeException();
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
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
          .withDescription("update deadline exceeded")
          .asRuntimeException();
    }
  }

  @Override
  public PollWorkflowExecutionUpdateResponse pollUpdateWorkflowExecution(
      PollWorkflowExecutionUpdateRequest request, Deadline deadline) {
    try {
      UpdateHandle updateHandle = getUpdate(request.getUpdateRef().getUpdateId());
      UpdateWorkflowExecutionResponse completionResponse =
          updateHandle
              .getCompletion()
              .get(
                  deadline != null ? deadline.timeRemaining(TimeUnit.MILLISECONDS) : Long.MAX_VALUE,
                  TimeUnit.MILLISECONDS);

      return PollWorkflowExecutionUpdateResponse.newBuilder()
          .setOutcome(completionResponse.getOutcome())
          .build();
    } catch (TimeoutException e) {
      return PollWorkflowExecutionUpdateResponse.getDefaultInstance();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof StatusRuntimeException) {
        throw (StatusRuntimeException) cause;
      }
      throw Status.INTERNAL
          .withCause(cause)
          .withDescription(cause.getMessage())
          .asRuntimeException();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  UpdateHandle getOrCreateUpdate(UpdateWorkflowExecutionRequest updateRequest) {
    // Before sending an update request, make sure the update does not
    // already exist
    lock.lock();
    String updateId = updateRequest.getRequest().getMeta().getUpdateId();
    try {
      Optional<UpdateWorkflowExecution> inflightUpdate =
          workflowTaskStateMachine.getData().getUpdateRequest(updateId);
      if (inflightUpdate.isPresent()) {
        return new UpdateHandle(
            inflightUpdate.get().getId(),
            inflightUpdate.get().getAcceptance(),
            inflightUpdate.get().getCompletion());
      }
      StateMachine<UpdateWorkflowExecutionData> acceptedUpdate = updates.get(updateId);
      if (acceptedUpdate != null) {
        return new UpdateHandle(
            acceptedUpdate.getData().id,
            acceptedUpdate.getData().acceptance,
            acceptedUpdate.getData().complete);
      }

      UpdateWorkflowExecution update = new UpdateWorkflowExecution(updateRequest);
      update(
          ctx -> {
            if (workflowTaskStateMachine.getState() == State.NONE) {
              scheduleWorkflowTask(ctx);
            }
            workflowTaskStateMachine.action(Action.UPDATE_WORKFLOW_EXECUTION, ctx, update, 0);
          });
      return new UpdateHandle(update.getId(), update.getAcceptance(), update.getCompletion());
    } finally {
      lock.unlock();
    }
  }

  UpdateHandle getUpdate(String updateId) {
    // Before sending an update request, make sure the update does not
    // already exist
    lock.lock();
    try {
      Optional<UpdateWorkflowExecution> inflightUpdate =
          workflowTaskStateMachine.getData().getUpdateRequest(updateId);
      if (inflightUpdate.isPresent()) {
        return new UpdateHandle(
            inflightUpdate.get().getId(),
            inflightUpdate.get().getAcceptance(),
            inflightUpdate.get().getCompletion());
      }
      StateMachine<UpdateWorkflowExecutionData> acceptedUpdate = updates.get(updateId);
      if (acceptedUpdate != null) {
        return new UpdateHandle(
            acceptedUpdate.getData().id,
            acceptedUpdate.getData().acceptance,
            acceptedUpdate.getData().complete);
      }
      throw Status.NOT_FOUND
          .withDescription("update " + updateId + " not found")
          .asRuntimeException();

    } finally {
      lock.unlock();
    }
  }

  static class CancelExternalWorkflowExecutionCallerInfo {
    private final String namespace;
    private final long externalInitiatedEventId;
    private final TestWorkflowMutableState caller;

    CancelExternalWorkflowExecutionCallerInfo(
        String namespace, long externalInitiatedEventId, TestWorkflowMutableState caller) {
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
    lock.lock();
    try {
      if (isTerminalState()) {
        return;
      }
      update(
          ctx -> {
            workflow.action(StateMachines.Action.REQUEST_CANCELLATION, ctx, cancelRequest, 0);
            scheduleWorkflowTask(ctx);
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
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void terminateWorkflowExecution(TerminateWorkflowExecutionRequest request) {
    update(
        ctx -> {
          workflow.action(Action.TERMINATE, ctx, request, 0);
          workflowTaskStateMachine.getData().workflowCompleted = true;
        });
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryRequest, long timeoutMs) {
    WorkflowExecutionStatus status = getWorkflowExecutionStatus();
    if (status != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
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
            || (workflowTaskStateMachine.getState() != State.INITIATED
                && workflowTaskStateMachine.getState() != State.STARTED);

    if (safeToDispatchDirectly) {
      return directQuery(queryRequest, timeoutMs);
    } else {
      return stronglyConsistentQuery(queryRequest, timeoutMs);
    }
  }

  private QueryWorkflowResponse directQuery(QueryWorkflowRequest queryRequest, long timeoutMs) {
    CompletableFuture<QueryWorkflowResponse> result = new CompletableFuture<>();
    try {
      QueryId queryId = new QueryId(executionId);
      PollWorkflowTaskQueueResponse.Builder task =
          PollWorkflowTaskQueueResponse.newBuilder()
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
      return result.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
          .withDescription("Query deadline of " + timeoutMs + " milliseconds exceeded")
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

  static class UpdateWorkflowExecution {
    private final String id;
    private final UpdateWorkflowExecutionRequest request;
    private final CompletableFuture<UpdateWorkflowExecutionResponse> acceptance =
        new CompletableFuture<>();
    private final CompletableFuture<UpdateWorkflowExecutionResponse> completion =
        new CompletableFuture<>();

    private UpdateWorkflowExecution(UpdateWorkflowExecutionRequest request) {
      this.request = request;
      String updateId = request.getRequest().getMeta().getUpdateId();
      this.id = updateId.isEmpty() ? UUID.randomUUID().toString() : updateId;
    }

    public UpdateWorkflowExecutionRequest getRequest() {
      return request;
    }

    public CompletableFuture<UpdateWorkflowExecutionResponse> getAcceptance() {
      return acceptance;
    }

    public CompletableFuture<UpdateWorkflowExecutionResponse> getCompletion() {
      return completion;
    }

    public String getId() {
      return id;
    }

    @Override
    public String toString() {
      return "UpdateWorkflowExecution{"
          + "id='"
          + id
          + '\''
          + ", request="
          + request
          + ", acceptance="
          + acceptance
          + ", completion="
          + completion
          + '}';
    }
  }

  static class UpdateHandle {
    private final String id;
    private final CompletableFuture<UpdateWorkflowExecutionResponse> acceptance;
    private final CompletableFuture<UpdateWorkflowExecutionResponse> completion;

    private UpdateHandle(
        String id,
        CompletableFuture<UpdateWorkflowExecutionResponse> acceptance,
        CompletableFuture<UpdateWorkflowExecutionResponse> completion) {
      this.id = id;
      this.acceptance = acceptance;
      this.completion = completion;
    }

    public CompletableFuture<UpdateWorkflowExecutionResponse> getAcceptance() {
      return acceptance;
    }

    public CompletableFuture<UpdateWorkflowExecutionResponse> getCompletion() {
      return completion;
    }

    public String getId() {
      return id;
    }
  }

  private QueryWorkflowResponse stronglyConsistentQuery(
      QueryWorkflowRequest queryRequest, long timeoutMs) {
    ConsistentQuery consistentQuery = new ConsistentQuery(queryRequest);
    try {
      update(ctx -> workflowTaskStateMachine.action(Action.QUERY, ctx, consistentQuery, 0));
    } finally {
      // Locked in the query method
      lock.unlock();
    }
    CompletableFuture<QueryWorkflowResponse> result = consistentQuery.getResult();
    return getQueryWorkflowResponse(timeoutMs, result);
  }

  private QueryWorkflowResponse getQueryWorkflowResponse(
      long timeoutMs, CompletableFuture<QueryWorkflowResponse> result) {
    try {
      return result.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
                QueryFailedFailure.getDefaultInstance(),
                QueryFailedFailure.getDescriptor());
        result.completeExceptionally(error);
        break;
    }
  }

  @Override
  public DescribeWorkflowExecutionResponse describeWorkflowExecution() {
    lock.lock();
    try {
      // pendingActivityInfo and childWorkflows are mutable, so we take the lock
      // before constructing a snapshot to avoid read skew or ConcurrentModificationExceptions
      return describeWorkflowExecutionInsideLock();
    } finally {
      lock.unlock();
    }
  }

  private DescribeWorkflowExecutionResponse describeWorkflowExecutionInsideLock() {
    WorkflowExecutionConfig.Builder executionConfig =
        WorkflowExecutionConfig.newBuilder()
            .setTaskQueue(this.startRequest.getTaskQueue())
            .setWorkflowExecutionTimeout(this.startRequest.getWorkflowExecutionTimeout())
            .setWorkflowRunTimeout(this.startRequest.getWorkflowRunTimeout())
            .setDefaultWorkflowTaskTimeout(this.startRequest.getWorkflowTaskTimeout());

    GetWorkflowExecutionHistoryRequest getRequest =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(this.startRequest.getNamespace())
            .setExecution(this.executionId.getExecution())
            .build();
    List<HistoryEvent> fullHistory =
        store
            .getWorkflowExecutionHistory(this.executionId, getRequest, null)
            .getHistory()
            .getEventsList();

    WorkflowExecutionInfo.Builder executionInfo = WorkflowExecutionInfo.newBuilder();
    executionInfo
        .setExecution(this.executionId.getExecution())
        .setType(this.getStartRequest().getWorkflowType())
        .setMemo(this.startRequest.getMemo())
        // No setAutoResetPoints - the test environment doesn't support that feature
        .setSearchAttributes(visibilityStore.getSearchAttributesForExecution(executionId))
        .setStatus(this.getWorkflowExecutionStatus())
        .setHistoryLength(fullHistory.size());

    populateWorkflowExecutionInfoFromHistory(executionInfo, fullHistory);

    this.parent.ifPresent(
        p ->
            executionInfo
                .setParentNamespaceId(p.getExecutionId().getNamespace())
                .setParentExecution(p.getExecutionId().getExecution()));

    List<PendingActivityInfo> pendingActivities =
        this.activities.values().stream()
            .filter(sm -> !isTerminalState(sm.getState()))
            .map(TestWorkflowMutableStateImpl::constructPendingActivityInfo)
            .collect(Collectors.toList());

    List<PendingChildExecutionInfo> pendingChildren =
        this.childWorkflows.values().stream()
            .filter(sm -> !isTerminalState(sm.getState()))
            .map(TestWorkflowMutableStateImpl::constructPendingChildExecutionInfo)
            .collect(Collectors.toList());

    return DescribeWorkflowExecutionResponse.newBuilder()
        .setExecutionConfig(executionConfig)
        .setWorkflowExecutionInfo(executionInfo)
        .addAllPendingActivities(pendingActivities)
        .addAllPendingChildren(pendingChildren)
        .build();
  }

  private static PendingChildExecutionInfo constructPendingChildExecutionInfo(
      StateMachine<ChildWorkflowData> sm) {
    ChildWorkflowData data = sm.getData();
    return PendingChildExecutionInfo.newBuilder()
        .setWorkflowId(data.execution.getWorkflowId())
        .setRunId(data.execution.getRunId())
        .setWorkflowTypeName(data.initiatedEvent.getWorkflowType().getName())
        .setInitiatedId(data.initiatedEventId)
        .setParentClosePolicy(data.initiatedEvent.getParentClosePolicy())
        .build();
  }

  private static PendingActivityInfo constructPendingActivityInfo(
      StateMachine<ActivityTaskData> sm) {
    // Working on this code? Read StateMachines.scheduleActivityTask to get answers to questions
    // like 'why does some of the information come from the scheduledEvent?'
    ActivityTaskData activityTaskData = sm.getData();

    State state = sm.getState();
    PendingActivityInfo.Builder builder = PendingActivityInfo.newBuilder();

    // The oddballs - these don't obviously come from any one part of the structure
    builder.setState(computeActivityState(state, activityTaskData));
    if (activityTaskData.identity != null) {
      builder.setLastWorkerIdentity(activityTaskData.identity);
    }

    // Some ids are only present in the schedule event...
    if (activityTaskData.scheduledEvent != null) {
      populatePendingActivityInfoFromScheduledEvent(builder, activityTaskData.scheduledEvent);
    }

    // A few bits of timing are only present on the poll response...
    if (activityTaskData.activityTask != null) {
      PollActivityTaskQueueResponseOrBuilder pollResponse = activityTaskData.activityTask.getTask();
      populatePendingActivityInfoFromPollResponse(builder, pollResponse);
    }

    // Heartbeat details are housed directly in the activityTaskData
    populatePendingActivityInfoFromHeartbeatDetails(builder, activityTaskData);

    // Retry data is housed under .retryState
    if (activityTaskData.retryState != null) {
      populatePendingActivityInfoFromRetryData(builder, activityTaskData.retryState);
    }

    return builder.build();
  }

  // Mimics golang in HistoryEngine.DescribeWorkflowExecution. Note that this only covers pending
  // states, so there's quite a bit of state-space that doesn't need to be mapped.
  private static PendingActivityState computeActivityState(
      State state, ActivityTaskData pendingActivity) {
    if (state == State.CANCELLATION_REQUESTED) {
      return PendingActivityState.PENDING_ACTIVITY_STATE_CANCEL_REQUESTED;
    } else if (pendingActivity.startedEvent != null) {
      return PendingActivityState.PENDING_ACTIVITY_STATE_STARTED;
    } else {
      return PendingActivityState.PENDING_ACTIVITY_STATE_SCHEDULED;
    }
  }

  private static void populatePendingActivityInfoFromScheduledEvent(
      PendingActivityInfo.Builder builder, ActivityTaskScheduledEventAttributes scheduledEvent) {
    builder
        .setActivityId(scheduledEvent.getActivityId())
        .setActivityType(scheduledEvent.getActivityType());
  }

  private static void populatePendingActivityInfoFromPollResponse(
      PendingActivityInfo.Builder builder, PollActivityTaskQueueResponseOrBuilder task) {
    // In golang, we set one but never both of these fields, depending on the activity state
    if (builder.getState() == PendingActivityState.PENDING_ACTIVITY_STATE_SCHEDULED) {
      builder.setScheduledTime(task.getScheduledTime());
    } else {
      builder.setLastStartedTime(task.getStartedTime());
    }
  }

  private static void populatePendingActivityInfoFromHeartbeatDetails(
      PendingActivityInfo.Builder builder, ActivityTaskData activityTaskData) {
    if (activityTaskData.lastHeartbeatTime > 0) {
      // This may overwrite the heartbeat time we just set - that's fine
      builder.setLastHeartbeatTime(Timestamps.fromMillis(activityTaskData.lastHeartbeatTime));

      if (activityTaskData.heartbeatDetails != null) {
        builder.setHeartbeatDetails(activityTaskData.heartbeatDetails);
      }
    }
  }

  private static void populatePendingActivityInfoFromRetryData(
      PendingActivityInfo.Builder builder, TestServiceRetryState retryState) {
    builder.setAttempt(retryState.getAttempt());
    builder.setExpirationTime(retryState.getExpirationTime());
    retryState.getPreviousRunFailure().ifPresent(builder::setLastFailure);

    RetryPolicy retryPolicy =
        Preconditions.checkNotNull(
            retryState.getRetryPolicy(), "retryPolicy should always be present");
    builder.setMaximumAttempts(retryPolicy.getMaximumAttempts());
  }

  private static void populateWorkflowExecutionInfoFromHistory(
      WorkflowExecutionInfo.Builder executionInfo, List<HistoryEvent> fullHistory) {
    getStartEvent(fullHistory)
        .ifPresent(
            startEvent -> {
              Timestamp startTime = startEvent.getEventTime();
              executionInfo.setStartTime(startEvent.getEventTime());

              if (startEvent
                  .getWorkflowExecutionStartedEventAttributes()
                  .hasFirstWorkflowTaskBackoff()) {
                executionInfo.setExecutionTime(
                    Timestamps.add(
                        startTime,
                        startEvent
                            .getWorkflowExecutionStartedEventAttributes()
                            .getFirstWorkflowTaskBackoff()));
              } else {
                // Some (most) workflows don't have firstWorkflowTaskBackoff.
                executionInfo.setExecutionTime(startTime);
              }
            });

    getCompletionEvent(fullHistory)
        .ifPresent(completionEvent -> executionInfo.setCloseTime(completionEvent.getEventTime()));
  }

  // Has an analog in the golang codebase: MutableState.GetStartEvent(). This could become public
  // if needed.
  private static Optional<HistoryEvent> getStartEvent(List<HistoryEvent> history) {
    if (history.size() == 0) {
      // It's theoretically possible for the TestWorkflowMutableState to exist, but
      // for the history to still be empty. This is the case between construction and
      // the ctx.commitChanges at the end of startWorkflow.
      return Optional.empty();
    }

    HistoryEvent firstEvent = history.get(0);

    // This is true today (see StateMachines.startWorkflow), even in the signalWithStartCase (signal
    // is the _second_ event). But if it becomes untrue in the future, we'd rather fail than lie.
    Preconditions.checkState(
        firstEvent.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED,
        "The first event in a workflow's history should be %s, but was %s",
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED.name(),
        firstEvent.getEventType().name());

    return Optional.of(firstEvent);
  }

  // Has an analog in the golang codebase: MutableState.GetCompletionEvent(). This could become
  // public if needed.
  private static Optional<HistoryEvent> getCompletionEvent(List<HistoryEvent> history) {
    HistoryEvent lastEvent = history.get(history.size() - 1);

    if (WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(lastEvent)) {
      return Optional.of(lastEvent);
    } else {
      return Optional.empty();
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
      RequestContext ctx, SignalExternalWorkflowExecutionCommandAttributes d) {
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

  private StateMachine<ActivityTaskData> getPendingActivityById(String activityId) {
    Long scheduledEventId = activityById.get(activityId);
    if (scheduledEventId == null) {
      throw Status.NOT_FOUND
          .withDescription(
              "cannot find pending activity with ActivityID "
                  + activityId
                  + ", check workflow execution history for more details")
          .asRuntimeException();
    }
    return getPendingActivityByScheduledEventId(scheduledEventId);
  }

  private void removeActivity(long scheduledEventId) {
    StateMachine<ActivityTaskData> activity = activities.remove(scheduledEventId);
    if (activity == null) {
      return;
    }
    activityById.remove(activity.getData().scheduledEvent.getActivityId());
  }

  private StateMachine<ActivityTaskData> getPendingActivityByScheduledEventId(
      long scheduledEventId) {
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

  /**
   * @throws StatusRuntimeException if the activity state is allowing heartbeats and other updates
   *     from the activity worker
   */
  private void throwIfActivityNotInFlightState(StateMachines.State activityState) {
    switch (activityState) {
      case STARTED:
      case CANCELLATION_REQUESTED:
        return;
      default:
        throw Status.NOT_FOUND
            .withDescription("Activity is in " + activityState + "  state")
            .asRuntimeException();
    }
  }

  private void throwIfTaskTokenDoesntMatch(ByteString taskToken, ActivityTaskData data) {
    if (!taskToken.isEmpty()) {
      ActivityTaskToken activityTaskToken = ActivityTaskToken.fromBytes(taskToken);
      if (activityTaskToken.getAttempt() != data.getAttempt()
          || activityTaskToken.getScheduledEventId() != data.scheduledEventId) {
        throw Status.NOT_FOUND
            .withDescription(
                "invalid activityID or activity already timed out or invoking workflow is completed")
            .asRuntimeException();
      }
    }
  }

  private boolean isTerminalState(State workflowState) {
    return workflowState == State.COMPLETED
        || workflowState == State.TIMED_OUT
        || workflowState == State.FAILED
        || workflowState == State.CANCELED
        || workflowState == State.TERMINATED
        || workflowState == State.CONTINUED_AS_NEW;
  }
}
