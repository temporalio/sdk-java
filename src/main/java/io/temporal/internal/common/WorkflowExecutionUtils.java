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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import io.grpc.Deadline;
import io.grpc.Status;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValue;
import io.temporal.common.converter.Value;
import io.temporal.failure.CanceledException;
import io.temporal.failure.TerminatedException;
import io.temporal.failure.TimeoutFailure;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.RetryStatus;
import io.temporal.proto.common.TimeoutType;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.decision.Decision;
import io.temporal.proto.decision.DecisionType;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.History;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.HistoryEventOrBuilder;
import io.temporal.proto.event.WorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.event.WorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.event.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.proto.event.WorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.WorkflowExecutionTerminatedEventAttributes;
import io.temporal.proto.event.WorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.execution.WorkflowExecutionInfo;
import io.temporal.proto.execution.WorkflowExecutionStatus;
import io.temporal.proto.filter.HistoryEventFilterType;
import io.temporal.proto.workflowservice.DescribeWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.DescribeWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryResponse;
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
   * Indentation for history and decisions pretty printing. Do not change it from 2 spaces. The gson
   * pretty printer has it hardcoded and changing it breaks the indentation of exception stack
   * traces.
   */
  private static final String INDENTATION = "  ";

  private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionUtils.class);

  private static RpcRetryOptions retryParameters =
      RpcRetryOptions.newBuilder()
          .setBackoffCoefficient(2)
          .setInitialInterval(Duration.ofMillis(500))
          .setMaximumInterval(Duration.ofSeconds(30))
          .setMaximumAttempts(Integer.MAX_VALUE)
          .addDoNotRetry(Status.Code.INVALID_ARGUMENT, null)
          .addDoNotRetry(Status.Code.NOT_FOUND, null)
          .build();

  /**
   * Returns result of a workflow instance execution or throws an exception if workflow did not
   * complete successfully.
   *
   * @param workflowType is optional.
   * @throws TimeoutException if workflow didn't complete within specified timeout
   * @throws CanceledException if workflow was cancelled
   * @throws WorkflowExecutionFailedException if workflow execution failed
   */
  public static Optional<Payloads> getWorkflowExecutionResult(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit,
      DataConverter converter)
      throws TimeoutException {
    // getIntanceCloseEvent waits for workflow completion including new runs.
    HistoryEvent closeEvent =
        getInstanceCloseEvent(service, namespace, workflowExecution, timeout, unit);
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
      case WorkflowExecutionCompleted:
        WorkflowExecutionCompletedEventAttributes completedEventAttributes =
            closeEvent.getWorkflowExecutionCompletedEventAttributes();
        if (completedEventAttributes.hasResult()) {
          return Optional.of(completedEventAttributes.getResult());
        }
        return Optional.empty();
      case WorkflowExecutionCanceled:
        String message = null;
        WorkflowExecutionCanceledEventAttributes attributes =
            closeEvent.getWorkflowExecutionCanceledEventAttributes();
        Optional<Payloads> details =
            attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
        throw new CanceledException(
            "Workflow canceled", new EncodedValue(details, converter), null);
      case WorkflowExecutionFailed:
        WorkflowExecutionFailedEventAttributes failed =
            closeEvent.getWorkflowExecutionFailedEventAttributes();
        throw new WorkflowExecutionFailedException(
            failed.getFailure(), failed.getDecisionTaskCompletedEventId(), failed.getRetryStatus());
      case WorkflowExecutionTerminated:
        WorkflowExecutionTerminatedEventAttributes terminated =
            closeEvent.getWorkflowExecutionTerminatedEventAttributes();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            RetryStatus.NonRetryableFailure,
            new TerminatedException(terminated.getReason(), null));
      case WorkflowExecutionTimedOut:
        WorkflowExecutionTimedOutEventAttributes timedOut =
            closeEvent.getWorkflowExecutionTimedOutEventAttributes();
        throw new WorkflowFailedException(
            workflowExecution,
            workflowType.orElse(null),
            0,
            timedOut.getRetryStatus(),
            new TimeoutFailure(null, Value.NULL, TimeoutType.StartToClose, null));
      default:
        throw new RuntimeException(
            "Workflow end state is not completed: " + prettyPrintObject(closeEvent));
    }
  }

  /** Returns an instance closing event, potentially waiting for workflow to complete. */
  public static HistoryEvent getInstanceCloseEvent(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    ByteString pageToken = ByteString.EMPTY;
    GetWorkflowExecutionHistoryResponse response = null;
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    HistoryEvent event;
    do {
      GetWorkflowExecutionHistoryRequest r =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setNamespace(namespace)
              .setExecution(workflowExecution)
              .setHistoryEventFilterType(HistoryEventFilterType.CloseEvent)
              .setWaitForNewEvent(true)
              .setNextPageToken(pageToken)
              .build();
      long elapsed = System.currentTimeMillis() - start;
      Deadline expiration = Deadline.after(unit.toMillis(timeout) - elapsed, TimeUnit.MILLISECONDS);
      if (expiration.timeRemaining(TimeUnit.MILLISECONDS) > 0) {
        RpcRetryOptions retryOptions =
            RpcRetryOptions.newBuilder()
                .setBackoffCoefficient(1)
                .setInitialInterval(Duration.ofMillis(1))
                .setMaximumAttempts(Integer.MAX_VALUE)
                .setExpiration(Duration.ofMillis(expiration.timeRemaining(TimeUnit.MILLISECONDS)))
                .addDoNotRetry(Status.Code.INVALID_ARGUMENT, null)
                .addDoNotRetry(Status.Code.NOT_FOUND, null)
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
                      .withDeadline(expirationInRetry)
                      .getWorkflowExecutionHistory(r);
                });
      }
      if (response == null || !response.hasHistory()) {
        continue;
      }
      if (timeout != 0 && System.currentTimeMillis() - start > unit.toMillis(timeout)) {
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
        event = history.getEvents(0);
        if (!isWorkflowExecutionCompletedEvent(event)) {
          throw new RuntimeException("Last history event is not completion event: " + event);
        }
        // Workflow called continueAsNew. Start polling the new generation with new runId.
        if (event.getEventType() == EventType.WorkflowExecutionContinuedAsNew) {
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
        break;
      }
    } while (true);
    return event;
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
            .setHistoryEventFilterType(HistoryEventFilterType.CloseEvent)
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
          if (event.getEventType() == EventType.WorkflowExecutionContinuedAsNew) {
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
        && (event.getEventType() == EventType.WorkflowExecutionCompleted
            || event.getEventType() == EventType.WorkflowExecutionCanceled
            || event.getEventType() == EventType.WorkflowExecutionFailed
            || event.getEventType() == EventType.WorkflowExecutionTimedOut
            || event.getEventType() == EventType.WorkflowExecutionContinuedAsNew
            || event.getEventType() == EventType.WorkflowExecutionTerminated));
  }

  public static boolean isWorkflowExecutionCompleteDecision(Decision decision) {
    return ((decision != null)
        && (decision.getDecisionType() == DecisionType.CompleteWorkflowExecution
            || decision.getDecisionType() == DecisionType.CancelWorkflowExecution
            || decision.getDecisionType() == DecisionType.FailWorkflowExecution
            || decision.getDecisionType() == DecisionType.ContinueAsNewWorkflowExecution));
  }

  public static boolean isActivityTaskClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.ActivityTaskCompleted
            || event.getEventType() == EventType.ActivityTaskCanceled
            || event.getEventType() == EventType.ActivityTaskFailed
            || event.getEventType() == EventType.ActivityTaskTimedOut));
  }

  public static boolean isExternalWorkflowClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.ChildWorkflowExecutionCompleted
            || event.getEventType() == EventType.ChildWorkflowExecutionCanceled
            || event.getEventType() == EventType.ChildWorkflowExecutionFailed
            || event.getEventType() == EventType.ChildWorkflowExecutionTerminated
            || event.getEventType() == EventType.ChildWorkflowExecutionTimedOut));
  }

  public static WorkflowExecution getWorkflowIdFromExternalWorkflowCompletedEvent(
      HistoryEvent event) {
    if (event != null) {
      if (event.getEventType() == EventType.ChildWorkflowExecutionCompleted) {
        return event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.ChildWorkflowExecutionCanceled) {
        return event.getChildWorkflowExecutionCanceledEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.ChildWorkflowExecutionFailed) {
        return event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.ChildWorkflowExecutionTerminated) {
        return event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.ChildWorkflowExecutionTimedOut) {
        return event.getChildWorkflowExecutionTimedOutEventAttributes().getWorkflowExecution();
      }
    }

    return null;
  }

  public static String getId(HistoryEvent historyEvent) {
    String id = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType() == EventType.StartChildWorkflowExecutionFailed) {
        id = historyEvent.getStartChildWorkflowExecutionFailedEventAttributes().getWorkflowId();
      }
    }

    return id;
  }

  public static String getFailureCause(HistoryEvent historyEvent) {
    String failureCause = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType() == EventType.StartChildWorkflowExecutionFailed) {
        failureCause =
            historyEvent
                .getStartChildWorkflowExecutionFailedEventAttributes()
                .getCause()
                .toString();
        //            } else if (historyEvent.getEventType() ==
        // EventType.SignalExternalWorkflowExecutionFailed) {
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
      WorkflowServiceStubs service, String namespace, WorkflowExecution workflowExecution) {
    try {
      return waitForWorkflowInstanceCompletion(
          service, namespace, workflowExecution, 0, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new Error("should never happen", e);
    }
  }

  /**
   * Waits up to specified timeout for workflow instance completion. <strong>Never</strong> use in
   * production setting as polling for worklow instance status is an expensive operation.
   *
   * @param workflowExecution workflowId and optional runId
   * @param timeout maximum time to wait for completion. 0 means wait forever.
   * @return instance close status
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletion(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    HistoryEvent closeEvent =
        getInstanceCloseEvent(service, namespace, workflowExecution, timeout, unit);
    return getCloseStatus(closeEvent);
  }

  public static WorkflowExecutionStatus getCloseStatus(HistoryEvent event) {
    switch (event.getEventType()) {
      case WorkflowExecutionCanceled:
        return WorkflowExecutionStatus.Canceled;
      case WorkflowExecutionFailed:
        return WorkflowExecutionStatus.Failed;
      case WorkflowExecutionTimedOut:
        return WorkflowExecutionStatus.TimedOut;
      case WorkflowExecutionContinuedAsNew:
        return WorkflowExecutionStatus.ContinuedAsNew;
      case WorkflowExecutionCompleted:
        return WorkflowExecutionStatus.Completed;
      case WorkflowExecutionTerminated:
        return WorkflowExecutionStatus.Terminated;
      default:
        throw new IllegalArgumentException("Not a close event: " + event);
    }
  }

  /**
   * Like {@link #waitForWorkflowInstanceCompletion(WorkflowServiceStubs, String, WorkflowExecution,
   * long, TimeUnit)} , except will wait for continued generations of the original workflow
   * execution too.
   *
   * @see #waitForWorkflowInstanceCompletion(WorkflowServiceStubs, String, WorkflowExecution, long,
   *     TimeUnit)
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletionAcrossGenerations(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {

    WorkflowExecution lastExecutionToRun = workflowExecution;
    long millisecondsAtFirstWait = System.currentTimeMillis();
    WorkflowExecutionStatus lastExecutionToRunCloseStatus =
        waitForWorkflowInstanceCompletion(service, namespace, lastExecutionToRun, timeout, unit);

    // keep waiting if the instance continued as new
    while (lastExecutionToRunCloseStatus == WorkflowExecutionStatus.ContinuedAsNew) {
      // get the new execution's information
      HistoryEvent closeEvent =
          getInstanceCloseEvent(service, namespace, lastExecutionToRun, timeout, unit);
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
              timeoutInSecondsForNextWait,
              TimeUnit.MILLISECONDS);
      lastExecutionToRun = newGenerationExecution;
    }

    return lastExecutionToRunCloseStatus;
  }

  /**
   * Like {@link #waitForWorkflowInstanceCompletion(WorkflowServiceStubs, String, WorkflowExecution,
   * long, TimeUnit)} , but with no timeout.*
   */
  public static WorkflowExecutionStatus waitForWorkflowInstanceCompletionAcrossGenerations(
      WorkflowServiceStubs service, String namespace, WorkflowExecution workflowExecution)
      throws InterruptedException {
    try {
      return waitForWorkflowInstanceCompletionAcrossGenerations(
          service, namespace, workflowExecution, 0L, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new Error("should never happen", e);
    }
  }

  public static WorkflowExecutionInfo describeWorkflowInstance(
      WorkflowServiceStubs service, String namespace, WorkflowExecution workflowExecution) {
    DescribeWorkflowExecutionRequest describeRequest =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .build();
    DescribeWorkflowExecutionResponse executionDetail =
        service.blockingStub().describeWorkflowExecution(describeRequest);
    WorkflowExecutionInfo instanceMetadata = executionDetail.getWorkflowExecutionInfo();
    return instanceMetadata;
  }

  public static GetWorkflowExecutionHistoryResponse getHistoryPage(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      ByteString nextPageToken) {
    GetWorkflowExecutionHistoryRequest getHistoryRequest =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .setNextPageToken(nextPageToken)
            .build();
    return service.blockingStub().getWorkflowExecutionHistory(getHistoryRequest);
  }

  /** Returns workflow instance history in a human readable format. */
  public static String prettyPrintHistory(
      WorkflowServiceStubs service, String namespace, WorkflowExecution workflowExecution) {
    return prettyPrintHistory(service, namespace, workflowExecution, true);
  }
  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (decider events) are not
   *     included
   */
  public static String prettyPrintHistory(
      WorkflowServiceStubs service,
      String namespace,
      WorkflowExecution workflowExecution,
      boolean showWorkflowTasks) {
    Iterator<HistoryEvent> events = getHistory(service, namespace, workflowExecution);
    return prettyPrintHistory(events, showWorkflowTasks);
  }

  public static Iterator<HistoryEvent> getHistory(
      WorkflowServiceStubs service, String namespace, WorkflowExecution workflowExecution) {
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
            getHistoryPage(service, namespace, workflowExecution, nextPageToken);
        current = history.getHistory().getEventsList().iterator();
        nextPageToken = history.getNextPageToken();
      }
    };
  }

  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (decider events) are not
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

  public static String prettyPrintDecisions(Iterable<Decision> decisions) {
    StringBuilder result = new StringBuilder();
    for (Decision decision : decisions) {
      result.append(prettyPrintObject(decision));
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

  /** Is this an event that was created to mirror a decision? */
  public static boolean isDecisionEvent(HistoryEvent event) {
    EventType eventType = event.getEventType();
    boolean result =
        ((event != null)
            && (eventType == EventType.ActivityTaskScheduled
                || eventType == EventType.StartChildWorkflowExecutionInitiated
                || eventType == EventType.TimerStarted
                || eventType == EventType.WorkflowExecutionCompleted
                || eventType == EventType.WorkflowExecutionFailed
                || eventType == EventType.WorkflowExecutionCanceled
                || eventType == EventType.WorkflowExecutionContinuedAsNew
                || eventType == EventType.ActivityTaskCancelRequested
                || eventType == EventType.RequestCancelActivityTaskFailed
                || eventType == EventType.TimerCanceled
                || eventType == EventType.CancelTimerFailed
                || eventType == EventType.RequestCancelExternalWorkflowExecutionInitiated
                || eventType == EventType.MarkerRecorded
                || eventType == EventType.SignalExternalWorkflowExecutionInitiated
                || eventType == EventType.UpsertWorkflowSearchAttributes));
    return result;
  }

  public static EventType getEventTypeForDecision(DecisionType decisionType) {
    switch (decisionType) {
      case ScheduleActivityTask:
        return EventType.ActivityTaskScheduled;
      case RequestCancelActivityTask:
        return EventType.ActivityTaskCancelRequested;
      case StartTimer:
        return EventType.TimerStarted;
      case CompleteWorkflowExecution:
        return EventType.WorkflowExecutionCompleted;
      case FailWorkflowExecution:
        return EventType.WorkflowExecutionFailed;
      case CancelTimer:
        return EventType.TimerCanceled;
      case CancelWorkflowExecution:
        return EventType.WorkflowExecutionCanceled;
      case RequestCancelExternalWorkflowExecution:
        return EventType.ExternalWorkflowExecutionCancelRequested;
      case RecordMarker:
        return EventType.MarkerRecorded;
      case ContinueAsNewWorkflowExecution:
        return EventType.WorkflowExecutionContinuedAsNew;
      case StartChildWorkflowExecution:
        return EventType.StartChildWorkflowExecutionInitiated;
      case SignalExternalWorkflowExecution:
        return EventType.SignalExternalWorkflowExecutionInitiated;
      case UpsertWorkflowSearchAttributes:
        return EventType.UpsertWorkflowSearchAttributes;
    }
    throw new IllegalArgumentException("Unknown decisionType");
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
}
