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

package io.temporal.internal.common;

import static io.temporal.serviceclient.MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import com.uber.m3.tally.Scope;
import io.grpc.Deadline;
import io.grpc.Status;
import io.temporal.api.command.v1.Command;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.HistoryEventOrBuilder;
import io.temporal.api.history.v1.WorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.serviceclient.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience methods to be used by unit tests and during development.
 *
 * @author fateev
 */
public class WorkflowExecutionUtils {

  /**
   * Indentation for history and commands pretty printing. Do not change it from 2 spaces. The gson
   * pretty printer has it hardcoded and changing it breaks the indentation of exception stack
   * traces.
   */
  private static final String INDENTATION = "  ";

  private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionUtils.class);

  private static final RpcRetryOptions GET_INSTANCE_CLOSE_EVENT_RETRY_OPTIONS =
      RpcRetryOptions.newBuilder()
          .setBackoffCoefficient(1)
          .setInitialInterval(Duration.ofMillis(1))
          .setMaximumAttempts(Integer.MAX_VALUE)
          .addDoNotRetry(Status.Code.INVALID_ARGUMENT, null)
          .addDoNotRetry(Status.Code.NOT_FOUND, null)
          .build();

  /**
   * Returns result of a workflow instance execution or throws an exception if workflow did not
   * complete successfully.
   *
   * @param workflowType is optional.
   * @param metricsScope metrics with NAMESPACE tag populated
   * @throws TimeoutException if workflow didn't complete within specified timeout
   * @throws CanceledFailure if workflow was canceled
   * @throws WorkflowExecutionFailedException if workflow execution failed
   */
  public static Optional<Payloads> getWorkflowExecutionResult(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      Scope metricsScope,
      DataConverter converter,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    // getIntanceCloseEvent waits for workflow completion including new runs.
    HistoryEvent closeEvent =
        getInstanceCloseEvent(service, namespace, workflowExecution, metricsScope, timeout, unit);
    return getResultFromCloseEvent(workflowExecution, workflowType, closeEvent, converter);
  }

  public static CompletableFuture<Optional<Payloads>> getWorkflowExecutionResultAsync(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit,
      DataConverter converter) {
    return getInstanceCloseEventAsync(service, namespace, workflowExecution, timeout, unit)
        .thenApply(
            (closeEvent) ->
                getResultFromCloseEvent(workflowExecution, workflowType, closeEvent, converter));
  }

  private static Optional<Payloads> getResultFromCloseEvent(
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      HistoryEvent closeEvent,
      DataConverter converter) {
    if (closeEvent == null) {
      throw new IllegalStateException("Workflow is still running");
    }
    switch (closeEvent.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        WorkflowExecutionCompletedEventAttributes completedEventAttributes =
            closeEvent.getWorkflowExecutionCompletedEventAttributes();
        if (completedEventAttributes.hasResult()) {
          return Optional.of(completedEventAttributes.getResult());
        }
        return Optional.empty();
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        String message = null;
        WorkflowExecutionCanceledEventAttributes attributes =
            closeEvent.getWorkflowExecutionCanceledEventAttributes();
        Optional<Payloads> details =
            attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new CanceledFailure("Workflow canceled", new EncodedValues(details, converter), null));
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        WorkflowExecutionFailedEventAttributes failed =
            closeEvent.getWorkflowExecutionFailedEventAttributes();
        throw new WorkflowExecutionFailedException(
            failed.getFailure(), failed.getWorkflowTaskCompletedEventId(), failed.getRetryState());
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        WorkflowExecutionTerminatedEventAttributes terminated =
            closeEvent.getWorkflowExecutionTerminatedEventAttributes();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
            new TerminatedFailure(terminated.getReason(), null));
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        WorkflowExecutionTimedOutEventAttributes timedOut =
            closeEvent.getWorkflowExecutionTimedOutEventAttributes();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            timedOut.getRetryState(),
            new TimeoutFailure(null, null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE));
      default:
        throw new RuntimeException(
            "Workflow end state is not completed: " + prettyPrintObject(closeEvent));
    }
  }

  /**
   * @param timeout timeout to retrieve InstanceCloseEvent in {@code unit} units. If 0 - MAX_INTEGER
   *     will be used
   * @param unit time unit of {@code timeout}
   * @return an instance closing event, potentially waiting for workflow to complete.
   */
  public static HistoryEvent getInstanceCloseEvent(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    ByteString pageToken = ByteString.EMPTY;
    GetWorkflowExecutionHistoryResponse response;
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    do {
      GetWorkflowExecutionHistoryRequest r =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(namespace)
              .setExecution(workflowExecution)
              .setHistoryEventFilterType(
                  HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
              .setWaitNewEvent(true)
              .setNextPageToken(pageToken)
              .build();

      long elapsed = System.currentTimeMillis() - start;
      long millisRemaining = unit.toMillis(timeout != 0 ? timeout : Integer.MAX_VALUE) - elapsed;

      if (millisRemaining > 0) {
        RpcRetryOptions retryOptions =
            RpcRetryOptions.newBuilder(GET_INSTANCE_CLOSE_EVENT_RETRY_OPTIONS)
                .setExpiration(Duration.ofMillis(millisRemaining))
                .build();
        response =
            GrpcRetryer.retryWithResult(
                retryOptions,
                () -> {
                  long elapsedInRetry = System.currentTimeMillis() - start;
                  Deadline expirationInRetry =
                      Deadline.after(
                          unit.toMillis(timeout) - elapsedInRetry, TimeUnit.MILLISECONDS);
                  return service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                      .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                      .withDeadline(expirationInRetry)
                      .getWorkflowExecutionHistory(r);
                });
        if (response == null || !response.hasHistory()) {
          continue;
        }
      } else {
        throw new TimeoutException(
            "WorkflowId="
                + workflowExecution.getWorkflowId()
                + ", runId="
                + workflowExecution.getRunId()
                + ", timeout="
                + timeout
                + ", unit="
                + unit);
      }

      pageToken = response.getNextPageToken();
      History history = response.getHistory();
      if (history.getEventsCount() > 0) {
        HistoryEvent event = history.getEvents(0);
        if (!isWorkflowExecutionCompletedEvent(event)) {
          throw new RuntimeException("Last history event is not completion event: " + event);
        }
        // Workflow called continueAsNew. Start polling the new generation with new runId.
        if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) {
          pageToken = ByteString.EMPTY;
          workflowExecution =
              WorkflowExecution.newBuilder()
                  .setWorkflowId(workflowExecution.getWorkflowId())
                  .setRunId(
                      event
                          .getWorkflowExecutionContinuedAsNewEventAttributes()
                          .getNewExecutionRunId())
                  .build();
          continue;
        }
        return event;
      }
    } while (true);
  }

  /** Returns an instance closing event, potentially waiting for workflow to complete. */
  private static CompletableFuture<HistoryEvent> getInstanceCloseEventAsync(
      WorkflowServiceStubs service,
      String namespace,
      final WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit) {
    return getInstanceCloseEventAsync(
        service, namespace, workflowExecution, ByteString.EMPTY, timeout, unit);
  }

  private static CompletableFuture<HistoryEvent> getInstanceCloseEventAsync(
      WorkflowServiceStubs service,
      String namespace,
      final WorkflowExecution workflowExecution,
      ByteString pageToken,
      long timeout,
      TimeUnit unit) {
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
            .setNextPageToken(pageToken)
            .build();
    CompletableFuture<GetWorkflowExecutionHistoryResponse> response =
        getWorkflowExecutionHistoryAsync(service, request, timeout, unit);
    return response.thenComposeAsync(
        (r) -> {
          if (timeout != 0 && System.currentTimeMillis() - start > unit.toMillis(timeout)) {
            throw CheckedExceptionWrapper.wrap(
                new TimeoutException(
                    "WorkflowId="
                        + workflowExecution.getWorkflowId()
                        + ", runId="
                        + workflowExecution.getRunId()
                        + ", timeout="
                        + timeout
                        + ", unit="
                        + unit));
          }
          History history = r.getHistory();
          if (history.getEventsCount() == 0) {
            // Empty poll returned
            return getInstanceCloseEventAsync(
                service, namespace, workflowExecution, pageToken, timeout, unit);
          }
          HistoryEvent event = history.getEvents(0);
          if (!isWorkflowExecutionCompletedEvent(event)) {
            throw new RuntimeException("Last history event is not completion event: " + event);
          }
          // Workflow called continueAsNew. Start polling the new generation with new runId.
          if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) {
            WorkflowExecution nextWorkflowExecution =
                WorkflowExecution.newBuilder()
                    .setWorkflowId(workflowExecution.getWorkflowId())
                    .setRunId(
                        event
                            .getWorkflowExecutionContinuedAsNewEventAttributes()
                            .getNewExecutionRunId())
                    .build();
            return getInstanceCloseEventAsync(
                service, namespace, nextWorkflowExecution, r.getNextPageToken(), timeout, unit);
          }
          return CompletableFuture.completedFuture(event);
        });
  }

  private static CompletableFuture<GetWorkflowExecutionHistoryResponse>
      getWorkflowExecutionHistoryAsync(
          WorkflowServiceStubs service,
          GetWorkflowExecutionHistoryRequest r,
          long timeout,
          TimeUnit unit) {
    long start = System.currentTimeMillis();
    Deadline expiration = Deadline.after(timeout, TimeUnit.MILLISECONDS);
    RpcRetryOptions retryOptions =
        RpcRetryOptions.newBuilder()
            .setBackoffCoefficient(1.5)
            .setInitialInterval(Duration.ofMillis(1))
            .setMaximumInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(Integer.MAX_VALUE)
            .setExpiration(Duration.ofMillis(expiration.timeRemaining(TimeUnit.MILLISECONDS)))
            .addDoNotRetry(Status.Code.INVALID_ARGUMENT, null)
            .addDoNotRetry(Status.Code.NOT_FOUND, null)
            .build();

    return GrpcRetryer.retryWithResultAsync(
        retryOptions,
        () -> {
          CompletableFuture<GetWorkflowExecutionHistoryResponse> result = new CompletableFuture<>();
          long elapsedInRetry = System.currentTimeMillis() - start;
          Deadline expirationInRetry =
              Deadline.after(unit.toMillis(timeout) - elapsedInRetry, TimeUnit.MILLISECONDS);
          ListenableFuture<GetWorkflowExecutionHistoryResponse> resultFuture =
              service.futureStub().withDeadline(expirationInRetry).getWorkflowExecutionHistory(r);
          resultFuture.addListener(
              () -> {
                try {
                  result.complete(resultFuture.get());
                } catch (ExecutionException e) {
                  result.completeExceptionally(e.getCause());
                } catch (Exception e) {
                  result.completeExceptionally(e);
                }
              },
              ForkJoinPool.commonPool());
          return result;
        });
  }

  public static boolean isWorkflowExecutionCompletedEvent(HistoryEventOrBuilder event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW
            || event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED));
  }

  public static boolean isWorkflowExecutionCompleteCommand(Command command) {
    return ((command != null)
        && (command.getCommandType() == CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION
            || command.getCommandType() == CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION
            || command.getCommandType() == CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION
            || command.getCommandType()
                == CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION));
  }

  public static boolean isActivityTaskClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED
            || event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT));
  }

  public static boolean isExternalWorkflowClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED
            || event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT));
  }

  public static WorkflowExecution getWorkflowIdFromExternalWorkflowCompletedEvent(
      HistoryEvent event) {
    if (event != null) {
      if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED) {
        return event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
        return event.getChildWorkflowExecutionCanceledEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED) {
        return event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED) {
        return event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT) {
        return event.getChildWorkflowExecutionTimedOutEventAttributes().getWorkflowExecution();
      }
    }

    return null;
  }

  public static String getId(HistoryEvent historyEvent) {
    String id = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType()
          == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED) {
        id = historyEvent.getStartChildWorkflowExecutionFailedEventAttributes().getWorkflowId();
      }
    }

    return id;
  }

  public static String getFailureCause(HistoryEvent historyEvent) {
    String failureCause = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType()
          == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED) {
        failureCause =
            historyEvent
                .getStartChildWorkflowExecutionFailedEventAttributes()
                .getCause()
                .toString();
        //            } else if (historyEvent.getEventType() ==
        // EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED) {
        //                failureCause =
        // historyEvent.getSignalExternalWorkflowExecutionFailedEventAttributes().getCause();
      } else {
        failureCause = "Cannot extract failure cause from " + historyEvent.getEventType();
      }
    }

    return failureCause;
  }

  /**
   * Blocks until workflow instance completes. <strong>Never</strong> use in production setting as
   * polling for worklow instance status is an expensive operation.
   *
   * @param workflowExecution workflowId and optional runId
   * @return instance close status
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletion(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    try {
      return waitForWorkflowInstanceCompletion(
          service, namespace, workflowExecution, metricsScope, 0, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new Error("should never happen", e);
    }
  }

  /**
   * Waits up to specified timeout for workflow instance completion. <strong>Never</strong> use in
   * production setting as polling for worklow instance status is an expensive operation.
   *
   * @param workflowExecution workflowId and optional runId
   * @param metricsScope to use when reporting service calls
   * @param timeout maximum time to wait for completion. 0 means wait forever.
   * @return instance close status
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletion(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    HistoryEvent closeEvent =
        getInstanceCloseEvent(service, namespace, workflowExecution, metricsScope, timeout, unit);
    return getCloseStatus(closeEvent);
  }

  public static WorkflowExecutionStatus getCloseStatus(HistoryEvent event) {
    switch (event.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED;
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW;
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED;
      default:
        throw new IllegalArgumentException("Not a close event: " + event);
    }
  }

  /**
   * Like {@link #waitForWorkflowInstanceCompletion(WorkflowServiceStubs, String, WorkflowExecution,
   * Scope, long, TimeUnit)} , except will wait for continued generations of the original workflow
   * execution too.
   *
   * @see #waitForWorkflowInstanceCompletion(WorkflowServiceStubs, String, WorkflowExecution, Scope,
   *     long, TimeUnit)
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletionAcrossGenerations(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {

    WorkflowExecution lastExecutionToRun = workflowExecution;
    long millisecondsAtFirstWait = System.currentTimeMillis();
    WorkflowExecutionStatus lastExecutionToRunCloseStatus =
        waitForWorkflowInstanceCompletion(
            service, namespace, lastExecutionToRun, metricsScope, timeout, unit);

    // keep waiting if the instance continued as new
    while (lastExecutionToRunCloseStatus
        == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW) {
      // get the new execution's information
      HistoryEvent closeEvent =
          getInstanceCloseEvent(
              service, namespace, lastExecutionToRun, metricsScope, timeout, unit);
      WorkflowExecutionContinuedAsNewEventAttributes continuedAsNewAttributes =
          closeEvent.getWorkflowExecutionContinuedAsNewEventAttributes();

      WorkflowExecution newGenerationExecution =
          WorkflowExecution.newBuilder()
              .setRunId(continuedAsNewAttributes.getNewExecutionRunId())
              .setWorkflowId(lastExecutionToRun.getWorkflowId())
              .build();

      // and wait for it
      long currentTime = System.currentTimeMillis();
      long millisecondsSinceFirstWait = currentTime - millisecondsAtFirstWait;
      long timeoutInSecondsForNextWait =
          unit.toMillis(timeout) - (millisecondsSinceFirstWait / 1000L);

      lastExecutionToRunCloseStatus =
          waitForWorkflowInstanceCompletion(
              service,
              namespace,
              newGenerationExecution,
              metricsScope,
              timeoutInSecondsForNextWait,
              TimeUnit.MILLISECONDS);
      lastExecutionToRun = newGenerationExecution;
    }

    return lastExecutionToRunCloseStatus;
  }

  /**
   * Like {@link #waitForWorkflowInstanceCompletion(WorkflowServiceStubs, String, WorkflowExecution,
   * Scope, long, TimeUnit)} , but with no timeout.*
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletionAcrossGenerations(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope)
      throws InterruptedException {
    try {
      return waitForWorkflowInstanceCompletionAcrossGenerations(
          service, namespace, workflowExecution, metricsScope, 0L, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new Error("should never happen", e);
    }
  }

  public static WorkflowExecutionInfo describeWorkflowInstance(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    DescribeWorkflowExecutionRequest describeRequest =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .build();
    DescribeWorkflowExecutionResponse executionDetail =
        service
            .blockingStub()
            .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
            .describeWorkflowExecution(describeRequest);
    WorkflowExecutionInfo instanceMetadata = executionDetail.getWorkflowExecutionInfo();
    return instanceMetadata;
  }

  public static GetWorkflowExecutionHistoryResponse getHistoryPage(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      ByteString nextPageToken,
      Scope metricsScope) {
    GetWorkflowExecutionHistoryRequest getHistoryRequest =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .setNextPageToken(nextPageToken)
            .build();
    return service
        .blockingStub()
        .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
        .getWorkflowExecutionHistory(getHistoryRequest);
  }

  /** Returns workflow instance history in a human readable format. */
  public static String prettyPrintHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    return prettyPrintHistory(service, namespace, workflowExecution, true, metricsScope);
  }
  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (command events) are not
   *     included
   * @param metricsScope
   */
  public static String prettyPrintHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      boolean showWorkflowTasks,
      Scope metricsScope) {
    Iterator<HistoryEvent> events = getHistory(service, namespace, workflowExecution, metricsScope);
    return prettyPrintHistory(events, showWorkflowTasks);
  }

  public static Iterator<HistoryEvent> getHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Scope metricsScope) {
    return new Iterator<HistoryEvent>() {
      ByteString nextPageToken = ByteString.EMPTY;
      Iterator<HistoryEvent> current;

      {
        getNextPage();
      }

      @Override
      public boolean hasNext() {
        return current.hasNext() || !nextPageToken.isEmpty();
      }

      @Override
      public HistoryEvent next() {
        if (current.hasNext()) {
          return current.next();
        }
        getNextPage();
        return current.next();
      }

      private void getNextPage() {
        GetWorkflowExecutionHistoryResponse history =
            getHistoryPage(service, namespace, workflowExecution, nextPageToken, metricsScope);
        current = history.getHistory().getEventsList().iterator();
        nextPageToken = history.getNextPageToken();
      }
    };
  }

  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (command events) are not
   *     included
   * @param history Workflow instance history
   */
  public static String prettyPrintHistory(History history, boolean showWorkflowTasks) {
    return prettyPrintHistory(history.getEventsList().iterator(), showWorkflowTasks);
  }

  public static String prettyPrintHistory(
      Iterator<HistoryEvent> events, boolean showWorkflowTasks) {
    StringBuilder result = new StringBuilder();
    while (events.hasNext()) {
      HistoryEvent event = events.next();
      if (!showWorkflowTasks && event.getEventType().toString().startsWith("WorkflowTask")) {
        continue;
      }
      result.append(prettyPrintObject(event));
    }
    return result.toString();
  }

  public static String prettyPrintCommands(Iterable<Command> commands) {
    StringBuilder result = new StringBuilder();
    for (Command command : commands) {
      result.append(prettyPrintObject(command));
    }
    return result.toString();
  }

  /** Pretty prints a proto message. */
  @SuppressWarnings("deprecation")
  public static String prettyPrintObject(MessageOrBuilder object) {
    return TextFormat.printToString(object);
  }

  public static boolean containsEvent(List<HistoryEvent> history, EventType eventType) {
    for (HistoryEvent event : history) {
      if (event.getEventType() == eventType) {
        return true;
      }
    }
    return false;
  }

  private static void fixStackTrace(JsonElement json, String stackIndentation) {
    if (!json.isJsonObject()) {
      return;
    }
    for (Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
      if ("stackTrace".equals(entry.getKey())) {
        String value = entry.getValue().getAsString();
        String replacement = "\n" + stackIndentation;
        String fixed = value.replaceAll("\\n", replacement);
        entry.setValue(new JsonPrimitive(fixed));
        continue;
      }
      fixStackTrace(entry.getValue(), stackIndentation + INDENTATION);
    }
  }

  /** Is this an event that was created to mirror a command? */
  public static boolean isCommandEvent(HistoryEvent event) {
    EventType eventType = event.getEventType();
    boolean result =
        ((event != null)
            && (eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED
                || eventType == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED
                || eventType == EventType.EVENT_TYPE_TIMER_STARTED
                || eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED
                || eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED
                || eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED
                || eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW
                || eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED
                || eventType == EventType.EVENT_TYPE_TIMER_CANCELED
                || eventType
                    == EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
                || eventType == EventType.EVENT_TYPE_MARKER_RECORDED
                || eventType == EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
                || eventType == EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES));
    return result;
  }

  /** Returns event that corresponds to a command. */
  public static EventType getEventTypeForCommand(CommandType commandType) {
    switch (commandType) {
      case COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK:
        return EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED;
      case COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK:
        return EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED;
      case COMMAND_TYPE_START_TIMER:
        return EventType.EVENT_TYPE_TIMER_STARTED;
      case COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED;
      case COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED;
      case COMMAND_TYPE_CANCEL_TIMER:
        return EventType.EVENT_TYPE_TIMER_CANCELED;
      case COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED;
      case COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED;
      case COMMAND_TYPE_RECORD_MARKER:
        return EventType.EVENT_TYPE_MARKER_RECORDED;
      case COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW;
      case COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED;
      case COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION:
        return EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED;
      case COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        return EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES;
    }
    throw new IllegalArgumentException("Unknown commandType");
  }

  public static WorkflowExecutionHistory readHistoryFromResource(String resourceFileName)
      throws IOException {
    ClassLoader classLoader = WorkflowExecutionUtils.class.getClassLoader();
    String historyUrl = classLoader.getResource(resourceFileName).getFile();
    File historyFile = new File(historyUrl);
    return readHistory(historyFile);
  }

  public static WorkflowExecutionHistory readHistory(File historyFile) throws IOException {
    try (Reader reader = Files.newBufferedReader(historyFile.toPath(), UTF_8)) {
      String jsonHistory = CharStreams.toString(reader);
      return WorkflowExecutionHistory.fromJson(jsonHistory);
    }
  }

  public static boolean isFullHistory(PollWorkflowTaskQueueResponseOrBuilder workflowTask) {
    return workflowTask.getHistory() != null
        && workflowTask.getHistory().getEventsCount() > 0
        && workflowTask.getHistory().getEvents(0).getEventId() == 1;
  }
}
