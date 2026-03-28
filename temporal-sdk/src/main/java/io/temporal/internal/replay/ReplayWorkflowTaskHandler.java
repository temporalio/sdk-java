package io.temporal.internal.replay;

import static io.temporal.internal.common.WorkflowExecutionUtils.isFullHistory;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Status;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.MeteringMetadata;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.QueryResultType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.sdk.v1.WorkflowTaskCompletedMetadata;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.BackoffThrottler;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.worker.*;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.NonDeterministicException;
import io.temporal.workflow.Functions;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayWorkflowTaskHandler implements WorkflowTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayWorkflowTaskHandler.class);
  private static final Duration MAX_STALE_HISTORY_RETRY_WINDOW = Duration.ofSeconds(10);
  private static final Duration STALE_HISTORY_RETRY_COMPLETION_BUFFER = Duration.ofSeconds(1);
  private static final Duration STALE_HISTORY_RETRY_INITIAL_INTERVAL = Duration.ofMillis(200);
  private static final Duration STALE_HISTORY_RETRY_MAX_INTERVAL = Duration.ofSeconds(4);
  private static final double STALE_HISTORY_RETRY_BACKOFF_COEFFICIENT = 2.0;
  private static final double STALE_HISTORY_RETRY_MAX_JITTER_COEFFICIENT = 0.2;

  private final ReplayWorkflowFactory workflowFactory;
  private final String namespace;
  private final WorkflowExecutorCache cache;
  private final SingleWorkerOptions options;
  private final Duration stickyTaskQueueScheduleToStartTimeout;
  private final WorkflowServiceStubs service;
  private final TaskQueue stickyTaskQueue;
  private final LocalActivityDispatcher localActivityDispatcher;

  public ReplayWorkflowTaskHandler(
      String namespace,
      ReplayWorkflowFactory asyncWorkflowFactory,
      WorkflowExecutorCache cache,
      SingleWorkerOptions options,
      TaskQueue stickyTaskQueue,
      Duration stickyTaskQueueScheduleToStartTimeout,
      WorkflowServiceStubs service,
      LocalActivityDispatcher localActivityDispatcher) {
    this.namespace = namespace;
    this.workflowFactory = asyncWorkflowFactory;
    this.cache = cache;
    this.options = options;
    this.stickyTaskQueue = stickyTaskQueue;
    this.stickyTaskQueueScheduleToStartTimeout = stickyTaskQueueScheduleToStartTimeout;
    this.service = Objects.requireNonNull(service);
    this.localActivityDispatcher = localActivityDispatcher;
  }

  @Override
  public WorkflowTaskHandler.Result handleWorkflowTask(PollWorkflowTaskQueueResponse workflowTask)
      throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    Scope metricsScope =
        options.getMetricsScope().tagged(ImmutableMap.of(MetricsTag.WORKFLOW_TYPE, workflowType));
    return handleWorkflowTaskWithQuery(workflowTask.toBuilder(), metricsScope);
  }

  private Result handleWorkflowTaskWithQuery(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Exception {
    PollWorkflowTaskQueueResponse originalWorkflowTask = workflowTask.build();
    boolean directQuery = originalWorkflowTask.hasQuery();
    WorkflowExecution execution = originalWorkflowTask.getWorkflowExecution();
    boolean useCache = stickyTaskQueue != null;
    BackoffThrottler staleHistoryRetryBackoff =
        new BackoffThrottler(
            STALE_HISTORY_RETRY_INITIAL_INTERVAL,
            STALE_HISTORY_RETRY_INITIAL_INTERVAL,
            STALE_HISTORY_RETRY_MAX_INTERVAL,
            STALE_HISTORY_RETRY_BACKOFF_COEFFICIENT,
            STALE_HISTORY_RETRY_MAX_JITTER_COEFFICIENT);
    int staleHistoryRetries = 0;
    long staleHistoryRetryDeadlineNanos = Long.MIN_VALUE;

    while (true) {
      PollWorkflowTaskQueueResponse.Builder attemptWorkflowTask = originalWorkflowTask.toBuilder();
      AtomicBoolean createdNew = new AtomicBoolean();
      WorkflowRunTaskHandler workflowRunTaskHandler = null;

      try {
        workflowRunTaskHandler =
            getOrCreateWorkflowExecutor(useCache, attemptWorkflowTask, metricsScope, createdNew);
        logWorkflowTaskToBeProcessed(attemptWorkflowTask, createdNew);

        ServiceWorkflowHistoryIterator historyIterator =
            new ServiceWorkflowHistoryIterator(
                service, namespace, attemptWorkflowTask, metricsScope);
        boolean finalCommand;
        Result result;

        if (directQuery) {
          // Direct query happens when there is no reason (events) to produce a real persisted
          // workflow task.
          // But Server needs to notify the workflow about the query and get back the query result.
          // Server creates a fake non-persisted a PollWorkflowTaskResponse with just the query.
          // This WFT has no new events in the history to process
          // and the worker response on such a WFT can't contain any new commands either.
          QueryResult queryResult =
              workflowRunTaskHandler.handleDirectQueryWorkflowTask(
                  attemptWorkflowTask, historyIterator);
          finalCommand = queryResult.isWorkflowMethodCompleted();
          result = createDirectQueryResult(attemptWorkflowTask, queryResult, null);
        } else {
          // main code path, handle workflow task that can have an embedded query
          WorkflowTaskResult wftResult =
              workflowRunTaskHandler.handleWorkflowTask(attemptWorkflowTask, historyIterator);
          finalCommand = wftResult.isFinalCommand();
          result =
              createCompletedWFTRequest(
                  attemptWorkflowTask.getWorkflowType().getName(),
                  attemptWorkflowTask,
                  wftResult,
                  workflowRunTaskHandler::resetStartedEventId);
        }

        if (useCache) {
          if (finalCommand) {
            // don't invalidate execution from the cache if we were not using cached value here
            cache.invalidate(execution, metricsScope, "FinalCommand", null);
          } else if (createdNew.get()) {
            cache.addToCache(execution, workflowRunTaskHandler);
          }
        }

        return result;
      } catch (InterruptedException e) {
        throw e;
      } catch (Throwable e) {
        boolean staleHistoryFailure =
            StaleWorkflowHistoryException.isStaleWorkflowHistoryFailure(e);
        long staleHistoryRetrySleepMillis = 0;
        if (staleHistoryFailure) {
          if (staleHistoryRetryDeadlineNanos == Long.MIN_VALUE) {
            staleHistoryRetryDeadlineNanos =
                System.nanoTime() + getStaleHistoryRetryWindow(attemptWorkflowTask).toNanos();
          }
          staleHistoryRetryBackoff.failure(Status.Code.UNKNOWN);
          staleHistoryRetrySleepMillis = staleHistoryRetryBackoff.getSleepTime();
        }
        // Note here that the executor might not be in the cache, even when the caching is on. In
        // that case we need to close the executor explicitly. For items in the cache, invalidation
        // callback will try to close again, which should be ok.
        if (workflowRunTaskHandler != null) {
          workflowRunTaskHandler.close();
          workflowRunTaskHandler = null;
        }

        if (staleHistoryFailure
            && shouldRetryStaleHistory(
                staleHistoryRetryDeadlineNanos, staleHistoryRetrySleepMillis)) {
          staleHistoryRetries++;
          if (useCache) {
            cache.invalidate(execution, metricsScope, "StaleHistory", e);
          }
          long remainingRetryMillis =
              Math.max(0, (staleHistoryRetryDeadlineNanos - System.nanoTime()) / 1_000_000);
          log.info(
              "Retrying workflow task after stale history response. startedEventId={}, WorkflowId={}, RunId={}, retryAttempt={}, remainingRetryMillis={}, reason={}",
              attemptWorkflowTask.getStartedEventId(),
              execution.getWorkflowId(),
              execution.getRunId(),
              staleHistoryRetries,
              remainingRetryMillis,
              e.getMessage());
          if (staleHistoryRetrySleepMillis > 0) {
            Thread.sleep(staleHistoryRetrySleepMillis);
          }
          continue;
        }

        if (useCache) {
          cache.invalidate(
              execution, metricsScope, staleHistoryFailure ? "StaleHistory" : "Exception", e);
          // If history is full and exception occurred then sticky session hasn't been established
          // yet, and we can avoid doing a reset.
          if (!isFullHistory(attemptWorkflowTask)) {
            resetStickyTaskQueue(execution);
          }
        }

        if (directQuery) {
          return createDirectQueryResult(attemptWorkflowTask, null, e);
        } else if (staleHistoryFailure) {
          // Stale history should not become a WORKFLOW_TASK_FAILED. Leave the task uncompleted and
          // rely on a later attempt to replay against a fresher history read.
          return createNoCompletionResult(attemptWorkflowTask);
        } else {
          // this call rethrows an exception in some scenarios
          DataConverter dataConverterWithWorkflowContext =
              options
                  .getDataConverter()
                  .withContext(
                      new WorkflowSerializationContext(namespace, execution.getWorkflowId()));
          return failureToWFTResult(attemptWorkflowTask, e, dataConverterWithWorkflowContext);
        }
      } finally {
        if (!useCache && workflowRunTaskHandler != null) {
          // we close the execution in finally only if we don't use cache, otherwise it stays open
          workflowRunTaskHandler.close();
        }
      }
    }
  }

  private Result createCompletedWFTRequest(
      String workflowType,
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      WorkflowTaskResult result,
      Functions.Proc1<Long> eventIdSetHandle) {
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    if (log.isTraceEnabled()) {
      log.trace(
          "WorkflowTask startedEventId="
              + workflowTask.getStartedEventId()
              + ", WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + " completed with \n"
              + WorkflowExecutionUtils.prettyPrintCommands(result.getCommands()));
    } else if (log.isDebugEnabled()) {
      log.debug(
          "WorkflowTask startedEventId="
              + workflowTask.getStartedEventId()
              + ", WorkflowId="
              + execution.getWorkflowId()
              + ", RunId="
              + execution.getRunId()
              + " completed with "
              + result.getCommands().size()
              + " new commands");
    }
    RespondWorkflowTaskCompletedRequest.Builder completedRequest =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .addAllCommands(result.getCommands())
            .addAllMessages(result.getMessages())
            .putAllQueryResults(result.getQueryResults())
            .setForceCreateNewWorkflowTask(result.isForceWorkflowTask())
            .setMeteringMetadata(
                MeteringMetadata.newBuilder()
                    .setNonfirstLocalActivityExecutionAttempts(
                        result.getNonfirstLocalActivityAttempts())
                    .build())
            .setReturnNewWorkflowTask(result.isForceWorkflowTask())
            .setVersioningBehavior(
                WorkerVersioningProtoUtils.behaviorToProto(result.getVersioningBehavior()))
            .setCapabilities(
                RespondWorkflowTaskCompletedRequest.Capabilities.newBuilder()
                    .setDiscardSpeculativeWorkflowTaskWithEvents(true)
                    .build());

    if (stickyTaskQueue != null
        && (stickyTaskQueueScheduleToStartTimeout == null
            || !stickyTaskQueueScheduleToStartTimeout.isZero())) {
      StickyExecutionAttributes.Builder attributes =
          StickyExecutionAttributes.newBuilder().setWorkerTaskQueue(stickyTaskQueue);
      if (stickyTaskQueueScheduleToStartTimeout != null) {
        attributes.setScheduleToStartTimeout(
            ProtobufTimeUtils.toProtoDuration(stickyTaskQueueScheduleToStartTimeout));
      }
      completedRequest.setStickyAttributes(attributes);
    }
    List<Integer> sdkFlags = result.getSdkFlags();
    String writeSdkName = result.getWriteSdkName();
    String writeSdkVersion = result.getWriteSdkVersion();
    if (!sdkFlags.isEmpty() || writeSdkName != null || writeSdkVersion != null) {
      WorkflowTaskCompletedMetadata.Builder md = WorkflowTaskCompletedMetadata.newBuilder();
      if (!sdkFlags.isEmpty()) {
        md.addAllLangUsedFlags(sdkFlags);
      }
      if (writeSdkName != null) {
        md.setSdkName(writeSdkName);
      }
      if (writeSdkVersion != null) {
        md.setSdkVersion(writeSdkVersion);
      }
      completedRequest.setSdkMetadata(md.build());
    }
    return new Result(
        workflowType,
        completedRequest.build(),
        null,
        null,
        null,
        result.isFinalCommand(),
        eventIdSetHandle,
        result.getApplyPostCompletionMetrics());
  }

  private Result failureToWFTResult(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, Throwable e, DataConverter dc)
      throws Exception {
    String workflowType = workflowTask.getWorkflowType().getName();
    if (e instanceof WorkflowExecutionException) {
      @SuppressWarnings("deprecation")
      RespondWorkflowTaskCompletedRequest response =
          RespondWorkflowTaskCompletedRequest.newBuilder()
              .setTaskToken(workflowTask.getTaskToken())
              .setIdentity(options.getIdentity())
              .setNamespace(namespace)
              .setBinaryChecksum(options.getBuildId())
              .addCommands(
                  Command.newBuilder()
                      .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
                      .setFailWorkflowExecutionCommandAttributes(
                          FailWorkflowExecutionCommandAttributes.newBuilder()
                              .setFailure(((WorkflowExecutionException) e).getFailure()))
                      .build())
              .build();
      return new WorkflowTaskHandler.Result(
          workflowType, response, null, null, null, false, null, null);
    }

    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    log.warn(
        "Workflow task processing failure. startedEventId={}, WorkflowId={}, RunId={}. If seen continuously the workflow might be stuck.",
        workflowTask.getStartedEventId(),
        execution.getWorkflowId(),
        execution.getRunId(),
        e);

    // Only fail workflow task on the first attempt, subsequent failures of the same workflow task
    // should timeout. This is to avoid spin on the failed workflow task as the service doesn't
    // yet increase the retry interval.
    if (workflowTask.getAttempt() > 1) {
      /*
       * TODO we shouldn't swallow Error even if workflowTask.getAttempt() == 1.
       *  But leaving as it is for now, because a trivial change to rethrow
       *  will leave us without reporting Errors as WorkflowTaskFailure to the server,
       *  which we probably should at least attempt to do for visibility that the Error occurs.
       */
      if (e instanceof Error) {
        throw (Error) e;
      }
      throw (Exception) e;
    }

    Failure failure = dc.exceptionToFailure(e);
    RespondWorkflowTaskFailedRequest.Builder failedRequest =
        RespondWorkflowTaskFailedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .setFailure(failure);
    if (e instanceof NonDeterministicException) {
      failedRequest.setCause(
          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR);
    } else {
      // Default task failure cause to "workflow worker unhandled failure"
      failedRequest.setCause(
          WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_WORKFLOW_WORKER_UNHANDLED_FAILURE);
    }
    return new WorkflowTaskHandler.Result(
        workflowType, null, failedRequest.build(), null, null, false, null, null);
  }

  private Result createDirectQueryResult(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask, QueryResult queryResult, Throwable e) {
    RespondQueryTaskCompletedRequest.Builder queryCompletedRequest =
        RespondQueryTaskCompletedRequest.newBuilder()
            .setTaskToken(workflowTask.getTaskToken())
            .setNamespace(namespace);

    if (e == null) {
      queryCompletedRequest.setCompletedType(QueryResultType.QUERY_RESULT_TYPE_ANSWERED);
      queryResult.getResponsePayloads().ifPresent(queryCompletedRequest::setQueryResult);
    } else {
      queryCompletedRequest.setCompletedType(QueryResultType.QUERY_RESULT_TYPE_FAILED);
      // TODO: Appropriate exception serialization.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);

      queryCompletedRequest.setErrorMessage(sw.toString());
    }

    return new Result(
        workflowTask.getWorkflowType().getName(),
        null,
        null,
        queryCompletedRequest.build(),
        null,
        false,
        null,
        null);
  }

  private Result createNoCompletionResult(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    return new Result(
        workflowTask.getWorkflowType().getName(), null, null, null, null, false, null, null);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return workflowFactory.isAnyTypeSupported();
  }

  private WorkflowRunTaskHandler getOrCreateWorkflowExecutor(
      boolean useCache,
      PollWorkflowTaskQueueResponse.Builder workflowTask,
      Scope metricsScope,
      AtomicBoolean createdNew)
      throws Exception {
    if (useCache) {
      return cache.getOrCreate(
          workflowTask,
          metricsScope,
          () -> {
            createdNew.set(true);
            return createStatefulHandler(workflowTask, metricsScope);
          });
    } else {
      createdNew.set(true);
      return createStatefulHandler(workflowTask, metricsScope);
    }
  }

  // TODO(maxim): Consider refactoring that avoids mutating workflow task.
  private WorkflowRunTaskHandler createStatefulHandler(
      PollWorkflowTaskQueueResponse.Builder workflowTask, Scope metricsScope) throws Exception {
    WorkflowType workflowType = workflowTask.getWorkflowType();
    WorkflowExecution workflowExecution = workflowTask.getWorkflowExecution();
    List<HistoryEvent> events = workflowTask.getHistory().getEventsList();
    // Sticky workflow task with partial history.
    if (events.isEmpty() || events.get(0).getEventId() > 1) {
      GetWorkflowExecutionHistoryRequest getHistoryRequest =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(namespace)
              .setExecution(workflowTask.getWorkflowExecution())
              .build();
      GetWorkflowExecutionHistoryResponse getHistoryResponse =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .getWorkflowExecutionHistory(getHistoryRequest);
      workflowTask
          .setHistory(getHistoryResponse.getHistory())
          .setNextPageToken(getHistoryResponse.getNextPageToken());
    }
    validateWorkflowTaskHistory(workflowTask);
    ReplayWorkflow workflow = workflowFactory.getWorkflow(workflowType, workflowExecution);
    return new ReplayWorkflowRunTaskHandler(
        namespace,
        workflow,
        workflowTask,
        options,
        metricsScope,
        localActivityDispatcher,
        service.getServerCapabilities().get());
  }

  private void validateWorkflowTaskHistory(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    List<HistoryEvent> events = workflowTask.getHistory().getEventsList();
    if (events.isEmpty()) {
      throw StaleWorkflowHistoryException.newPrematureEndOfStream(
          workflowTask.getStartedEventId(), 0);
    }
    if (events.get(0).getEventId() > 1) {
      throw StaleWorkflowHistoryException.newIncompleteFullHistory(events.get(0).getEventId());
    }

    long startedEventId = workflowTask.getStartedEventId();
    long lastEventId = events.get(events.size() - 1).getEventId();
    if (startedEventId != Long.MAX_VALUE
        && startedEventId > 0
        && workflowTask.getNextPageToken().isEmpty()
        && lastEventId < startedEventId) {
      throw StaleWorkflowHistoryException.newPrematureEndOfStream(startedEventId, lastEventId);
    }
  }

  private boolean shouldRetryStaleHistory(
      long staleHistoryRetryDeadlineNanos, long staleHistoryRetrySleepMillis) {
    long nowNanos = System.nanoTime();
    if (staleHistoryRetryDeadlineNanos == Long.MIN_VALUE
        || nowNanos >= staleHistoryRetryDeadlineNanos) {
      return false;
    }
    return nowNanos + TimeUnit.MILLISECONDS.toNanos(Math.max(0, staleHistoryRetrySleepMillis))
        < staleHistoryRetryDeadlineNanos;
  }

  private Duration getStaleHistoryRetryWindow(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    Duration retryWindow = MAX_STALE_HISTORY_RETRY_WINDOW;
    List<HistoryEvent> events = workflowTask.getHistory().getEventsList();
    if (!events.isEmpty() && events.get(0).hasWorkflowExecutionStartedEventAttributes()) {
      Duration workflowTaskTimeout =
          ProtobufTimeUtils.toJavaDuration(
              events.get(0).getWorkflowExecutionStartedEventAttributes().getWorkflowTaskTimeout());
      if (!workflowTaskTimeout.isZero() && workflowTaskTimeout.compareTo(retryWindow) < 0) {
        retryWindow = workflowTaskTimeout;
      }
    }
    if (retryWindow.compareTo(STALE_HISTORY_RETRY_COMPLETION_BUFFER) <= 0) {
      return Duration.ZERO;
    }
    return retryWindow.minus(STALE_HISTORY_RETRY_COMPLETION_BUFFER);
  }

  private void resetStickyTaskQueue(WorkflowExecution execution) {
    service
        .futureStub()
        .resetStickyTaskQueue(
            ResetStickyTaskQueueRequest.newBuilder()
                .setNamespace(namespace)
                .setExecution(execution)
                .build());
  }

  private void logWorkflowTaskToBeProcessed(
      PollWorkflowTaskQueueResponse.Builder workflowTask, AtomicBoolean createdNew) {
    if (log.isDebugEnabled()) {
      boolean directQuery = workflowTask.hasQuery();
      WorkflowExecution execution = workflowTask.getWorkflowExecution();
      if (directQuery) {
        log.debug(
            "Handle Direct Query {}. WorkflowId='{}', RunId='{}', queryType='{}', startedEventId={}, previousStartedEventId={}",
            createdNew.get() ? "with new executor" : "with existing executor",
            execution.getWorkflowId(),
            execution.getRunId(),
            workflowTask.getQuery().getQueryType(),
            workflowTask.getStartedEventId(),
            workflowTask.getPreviousStartedEventId());
      } else {
        log.debug(
            "Handle Workflow Task {}. {}WorkflowId='{}', RunId='{}', TaskQueue='{}', startedEventId='{}', previousStartedEventId:{}",
            createdNew.get() ? "with new executor" : "with existing executor",
            workflowTask.getQueriesMap().isEmpty()
                ? ""
                : "With queries: "
                    + workflowTask.getQueriesMap().values().stream()
                        .map(WorkflowQuery::getQueryType)
                        .collect(Collectors.toList())
                    + ". ",
            execution.getWorkflowId(),
            execution.getRunId(),
            workflowTask.getWorkflowExecutionTaskQueue().getName(),
            workflowTask.getStartedEventId(),
            workflowTask.getPreviousStartedEventId());
      }
    }
  }
}
