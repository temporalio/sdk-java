package io.temporal.internal.replay;

import static io.temporal.internal.common.ProtobufTimeUtils.toJavaDuration;
import static io.temporal.serviceclient.CheckedExceptionWrapper.wrap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import io.grpc.Deadline;
import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.protocol.v1.Message;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.query.v1.WorkflowQueryResult;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.Config;
import io.temporal.internal.common.FailureUtils;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.common.UpdateMessage;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.internal.statemachines.StatesMachinesCallback;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.*;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements workflow executor that relies on replay of a workflow code. An instance of this class
 * is created per cached workflow run.
 */
class ReplayWorkflowRunTaskHandler implements WorkflowRunTaskHandler {
  private final Scope metricsScope;

  private final WorkflowExecutionStartedEventAttributes startedEvent;

  private final Lock lock = new ReentrantLock();

  private final Functions.Proc1<LocalActivityResult> localActivityCompletionSink;

  private final BlockingQueue<LocalActivityResult> localActivityCompletionQueue =
      new LinkedBlockingDeque<>();

  private final LocalActivityDispatcher localActivityDispatcher;

  private final LocalActivityMeteringHelper localActivityMeteringHelper;

  private final ReplayWorkflow workflow;

  private final WorkflowStateMachines workflowStateMachines;

  /** Number of non completed local activity tasks */
  // TODO move and maintain this counter inside workflowStateMachines
  private int localActivityTaskCount;

  private final ReplayWorkflowContextImpl context;

  private final ReplayWorkflowExecutor replayWorkflowExecutor;

  private final GetSystemInfoResponse.Capabilities capabilities;

  ReplayWorkflowRunTaskHandler(
      String namespace,
      ReplayWorkflow workflow,
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      SingleWorkerOptions workerOptions,
      Scope metricsScope,
      LocalActivityDispatcher localActivityDispatcher,
      GetSystemInfoResponse.Capabilities capabilities) {
    HistoryEvent startedEvent = workflowTask.getHistory().getEvents(0);
    if (!startedEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException(
          "First event in the history is not WorkflowExecutionStarted");
    }
    this.startedEvent = startedEvent.getWorkflowExecutionStartedEventAttributes();
    this.metricsScope = metricsScope;
    this.localActivityDispatcher = localActivityDispatcher;
    this.workflow = workflow;

    this.workflowStateMachines =
        new WorkflowStateMachines(
            new StatesMachinesCallbackImpl(),
            capabilities,
            workflow.getWorkflowContext() == null
                ? WorkflowImplementationOptions.newBuilder().build()
                : workflow.getWorkflowContext().getWorkflowImplementationOptions());
    String fullReplayDirectQueryType =
        workflowTask.hasQuery() ? workflowTask.getQuery().getQueryType() : null;
    this.context =
        new ReplayWorkflowContextImpl(
            workflowStateMachines,
            namespace,
            this.startedEvent,
            workflowTask.getWorkflowExecution(),
            Timestamps.toMillis(startedEvent.getEventTime()),
            fullReplayDirectQueryType,
            workerOptions,
            metricsScope);

    this.replayWorkflowExecutor =
        new ReplayWorkflowExecutor(workflow, workflowStateMachines, context);
    this.localActivityCompletionSink = localActivityCompletionQueue::add;
    this.localActivityMeteringHelper = new LocalActivityMeteringHelper();
    this.capabilities = capabilities;
  }

  @Override
  public WorkflowTaskResult handleWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowHistoryIterator historyIterator)
      throws Throwable {
    lock.lock();
    try {
      localActivityMeteringHelper.newWFTStarting();

      Deadline wftHearbeatDeadline =
          Deadline.after(
              (long)
                  (Durations.toNanos(startedEvent.getWorkflowTaskTimeout())
                      * Config.WORKFLOW_TASK_HEARTBEAT_COEFFICIENT),
              TimeUnit.NANOSECONDS);

      if (workflowTask.getPreviousStartedEventId()
          < workflowStateMachines.getLastWFTStartedEventId()) {
        // if previousStartedEventId < currentStartedEventId - the last workflow task handled by
        // these state machines is ahead of the last handled workflow task known by the server.
        // Something is off, the server lost progress.
        // If the fact that we error out here becomes undesirable, because we fail the workflow
        // task,
        // we always can rework it to graceful invalidation of the cache entity and a full replay
        // from the server
        throw new IllegalStateException(
            "Server history for the workflow is below the progress of the workflow on the worker, the progress needs to be discarded");
      }

      handleWorkflowTaskImpl(workflowTask, historyIterator);
      processLocalActivityRequests(wftHearbeatDeadline);
      List<Command> commands = workflowStateMachines.takeCommands();
      List<Message> messages = workflowStateMachines.takeMessages();
      EnumSet<SdkFlag> newFlags = workflowStateMachines.takeNewSdkFlags();
      List<Integer> newSdkFlags = new ArrayList<>(newFlags.size());
      for (SdkFlag flag : newFlags) {
        newSdkFlags.add(flag.getValue());
      }
      if (context.isWorkflowMethodCompleted()) {
        // it's important for query, otherwise the WorkflowTaskHandler is responsible for closing
        // and invalidation
        close();
      }
      if (context.getWorkflowTaskFailure() != null) {
        throw context.getWorkflowTaskFailure();
      }
      Map<String, WorkflowQueryResult> queryResults = executeQueries(workflowTask.getQueriesMap());
      WorkflowTaskResult.Builder result =
          WorkflowTaskResult.newBuilder()
              .setCommands(commands)
              .setMessages(messages)
              .setQueryResults(queryResults)
              .setFinalCommand(context.isWorkflowMethodCompleted())
              .setForceWorkflowTask(
                  localActivityTaskCount > 0 && !context.isWorkflowMethodCompleted())
              .setNonfirstLocalActivityAttempts(localActivityMeteringHelper.getNonfirstAttempts())
              .setSdkFlags(newSdkFlags);
      if (workflowStateMachines.sdkNameToWrite() != null) {
        result.setWriteSdkName(workflowStateMachines.sdkNameToWrite());
      }
      if (workflowStateMachines.sdkVersionToWrite() != null) {
        result.setWriteSdkVersion(workflowStateMachines.sdkVersionToWrite());
      }
      if (workflow.getWorkflowContext() != null) {
        result.setVersioningBehavior(workflow.getWorkflowContext().getVersioningBehavior());
      }
      return result.build();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public QueryResult handleDirectQueryWorkflowTask(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, WorkflowHistoryIterator historyIterator)
      throws Throwable {
    WorkflowQuery query = workflowTask.getQuery();
    lock.lock();
    try {
      handleWorkflowTaskImpl(workflowTask, historyIterator);
      if (context.isWorkflowMethodCompleted()) {
        // it's important for query, otherwise the WorkflowTaskHandler is responsible for closing
        // and invalidation
        close();
      }
      if (context.getWorkflowTaskFailure() != null) {
        throw context.getWorkflowTaskFailure();
      }
      Optional<Payloads> resultPayloads = replayWorkflowExecutor.query(query);
      return new QueryResult(resultPayloads, context.isWorkflowMethodCompleted());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void resetStartedEventId(Long eventId) {
    workflowStateMachines.resetStartedEventId(eventId);
  }

  private void handleWorkflowTaskImpl(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      WorkflowHistoryIterator historyIterator) {
    workflowStateMachines.setWorkflowStartedEventId(workflowTask.getStartedEventId());
    workflowStateMachines.setReplaying(workflowTask.getPreviousStartedEventId() > 0);
    workflowStateMachines.setMessages(workflowTask.getMessagesList());
    applyServerHistory(workflowTask.getStartedEventId(), historyIterator);
  }

  private void applyServerHistory(long lastEventId, WorkflowHistoryIterator historyIterator) {
    Duration expiration = toJavaDuration(startedEvent.getWorkflowTaskTimeout());
    historyIterator.initDeadline(Deadline.after(expiration.toMillis(), TimeUnit.MILLISECONDS));

    boolean timerStopped = false;
    Stopwatch sw = metricsScope.timer(MetricsType.WORKFLOW_TASK_REPLAY_LATENCY).start();
    long currentEventId = 0;
    try {
      while (historyIterator.hasNext()) {
        // iteration itself is intentionally left outside the try-catch below,
        // as gRPC exception happened during history iteration should never ever fail the workflow
        HistoryEvent event = historyIterator.next();
        currentEventId = event.getEventId();
        boolean hasNext = historyIterator.hasNext();
        try {
          workflowStateMachines.handleEvent(event, hasNext);
        } catch (Throwable e) {
          // Fail workflow if exception is of the specified type
          WorkflowImplementationOptions implementationOptions =
              workflow.getWorkflowContext().getWorkflowImplementationOptions();
          Class<? extends Throwable>[] failTypes =
              implementationOptions.getFailWorkflowExceptionTypes();
          for (Class<? extends Throwable> failType : failTypes) {
            if (failType.isAssignableFrom(e.getClass())) {
              if (!FailureUtils.isBenignApplicationFailure(e)) {
                metricsScope.counter(MetricsType.WORKFLOW_FAILED_COUNTER).inc(1);
              }
              throw new WorkflowExecutionException(
                  workflow.getWorkflowContext().mapWorkflowExceptionToFailure(e));
            }
          }
          if (e instanceof WorkflowExecutionException
              && !FailureUtils.isBenignApplicationFailure(e)) {
            metricsScope.counter(MetricsType.WORKFLOW_FAILED_COUNTER).inc(1);
          }
          throw wrap(e);
        }
        if (!timerStopped && !workflowStateMachines.isReplaying()) {
          sw.stop();
          timerStopped = true;
        }
      }
      verifyAllEventsProcessed(lastEventId, currentEventId);
    } finally {
      if (!timerStopped) {
        sw.stop();
      }
    }
  }

  // Verify the received and processed all events up to the last one we knew about from the polled
  // task.
  // It is possible for the server to send fewer events than required if we are reading history from
  // a stale node.
  private void verifyAllEventsProcessed(long lastEventId, long processedEventId) {
    if (lastEventId != Long.MAX_VALUE && lastEventId > 0 && processedEventId < lastEventId) {
      throw new IllegalStateException(
          String.format(
              "Premature end of stream, expectedLastEventID=%d but no more events after eventID=%d",
              lastEventId, processedEventId));
    }
  }

  private Map<String, WorkflowQueryResult> executeQueries(Map<String, WorkflowQuery> queries) {
    Map<String, WorkflowQueryResult> queryResults = new HashMap<>();
    for (Map.Entry<String, WorkflowQuery> entry : queries.entrySet()) {
      WorkflowQuery query = entry.getValue();
      try {
        Optional<Payloads> queryResult = replayWorkflowExecutor.query(query);
        WorkflowQueryResult.Builder result =
            WorkflowQueryResult.newBuilder()
                .setResultType(QueryResultType.QUERY_RESULT_TYPE_ANSWERED);
        if (queryResult.isPresent()) {
          result.setAnswer(queryResult.get());
        }
        queryResults.put(entry.getKey(), result.build());
      } catch (Exception e) {
        String stackTrace = Throwables.getStackTraceAsString(e);
        queryResults.put(
            entry.getKey(),
            WorkflowQueryResult.newBuilder()
                .setResultType(QueryResultType.QUERY_RESULT_TYPE_FAILED)
                .setErrorMessage(e + "\n" + stackTrace)
                .build());
      }
    }
    return queryResults;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      replayWorkflowExecutor.close();
    } finally {
      lock.unlock();
    }
  }

  private void processLocalActivityRequests(Deadline wftHeartbeatDeadline)
      throws InterruptedException, Throwable {

    while (true) {
      // Scheduling or handling any local activities after the workflow method has returned
      // can result in commands being generated after the CompleteWorkflowExecution command
      // which the server does not allow.
      if (context.isWorkflowMethodCompleted()) {
        break;
      }
      List<ExecuteLocalActivityParameters> laRequests =
          workflowStateMachines.takeLocalActivityRequests();
      localActivityTaskCount += laRequests.size();

      for (ExecuteLocalActivityParameters laRequest : laRequests) {
        boolean accepted =
            localActivityDispatcher.dispatch(
                laRequest, localActivityCompletionSink, wftHeartbeatDeadline);
        // TODO do we have to fail? if we didn't fit in a potentially tight timeout left until
        // wftHeartbeatDeadline,
        //  maybe we can return control, heartbeat and try again with fresh timeout one more time?
        Preconditions.checkState(
            accepted,
            "Unable to schedule local activity for execution, "
                + "no more slots available and local activity task queue is full");

        localActivityMeteringHelper.addNewLocalActivity(laRequest);
      }

      if (localActivityTaskCount == 0) {
        // No outstanding local activity requests
        break;
      }

      long maxWaitTimeTillHeartbeatNs = wftHeartbeatDeadline.timeRemaining(TimeUnit.NANOSECONDS);
      LocalActivityResult laCompletion =
          localActivityCompletionQueue.poll(maxWaitTimeTillHeartbeatNs, TimeUnit.NANOSECONDS);
      if (laCompletion == null) {
        // Need to force a new task as we are out of time
        break;
      }

      localActivityTaskCount--;
      localActivityMeteringHelper.markLocalActivityComplete(laCompletion.getActivityId());

      if (laCompletion.getProcessingError() != null) {
        throw laCompletion.getProcessingError().getThrowable();
      }

      workflowStateMachines.handleLocalActivityCompletion(laCompletion);
      // handleLocalActivityCompletion triggers eventLoop.
      // After this call, there may be new local activity requests available in
      // workflowStateMachines.takeLocalActivityRequests()
      // These requests need to be processed and accounted for, otherwise we may end up not
      // heartbeating and completing workflow task instead. So we have to make another iteration.
    }

    // it's safe to call and discard the result of takeLocalActivityRequests() here, because if it's
    // not empty - we are in trouble anyway
    Preconditions.checkState(
        workflowStateMachines.takeLocalActivityRequests().isEmpty()
            || context.isWorkflowMethodCompleted(),
        "[BUG] Local activities requests from the last event loop were not drained "
            + "and accounted in the outstanding local activities counter");
  }

  @VisibleForTesting
  WorkflowStateMachines getWorkflowStateMachines() {
    return workflowStateMachines;
  }

  private class StatesMachinesCallbackImpl implements StatesMachinesCallback {

    @Override
    public void start(HistoryEvent startWorkflowEvent) {
      replayWorkflowExecutor.start(startWorkflowEvent);
    }

    @Override
    public void eventLoop() {
      replayWorkflowExecutor.eventLoop();
    }

    @Override
    public void signal(HistoryEvent signalEvent) {
      replayWorkflowExecutor.handleWorkflowExecutionSignaled(signalEvent);
    }

    @Override
    public void update(UpdateMessage message) {
      replayWorkflowExecutor.handleWorkflowExecutionUpdated(message);
    }

    @Override
    public void cancel(HistoryEvent cancelEvent) {
      replayWorkflowExecutor.handleWorkflowExecutionCancelRequested(cancelEvent);
    }
  }

  @VisibleForTesting
  static class LocalActivityMeteringHelper {
    private final Map<String, AtomicInteger> firstWftActivities = new HashMap<>();
    private final Map<String, AtomicInteger> nonFirstWftActivities = new HashMap<>();
    private final Set<String> completed = new HashSet<>();

    void newWFTStarting() {
      for (String activityId : firstWftActivities.keySet()) {
        AtomicInteger attemptCount = firstWftActivities.get(activityId);
        attemptCount.set(0);
        nonFirstWftActivities.put(activityId, attemptCount);
      }
      firstWftActivities.clear();
    }

    void addNewLocalActivity(ExecuteLocalActivityParameters params) {
      AtomicInteger attemptsDuringWFTCounter = new AtomicInteger(0);
      params.setOnNewAttemptCallback(attemptsDuringWFTCounter::incrementAndGet);
      firstWftActivities.put(params.getActivityId(), attemptsDuringWFTCounter);
    }

    void markLocalActivityComplete(String activityId) {
      completed.add(activityId);
    }

    int getNonfirstAttempts() {
      int result =
          nonFirstWftActivities.values().stream()
              .map(ai -> ai.getAndSet(0))
              .reduce(0, Integer::sum);
      for (String activityId : completed) {
        firstWftActivities.remove(activityId);
        nonFirstWftActivities.remove(activityId);
      }
      completed.clear();
      return result;
    }
  }
}
