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

package io.temporal.internal.common;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;
import com.spotify.futures.ListenableFuturesExtra;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.*;
import io.temporal.client.WorkflowTerminatedException;
import io.temporal.client.WorkflowTimedOutException;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

  private static RetryOptions retryParameters =
      new RetryOptions.Builder()
          .setBackoffCoefficient(2)
          .setInitialInterval(Duration.ofMillis(500))
          .setMaximumInterval(Duration.ofSeconds(30))
          .setMaximumAttempts(Integer.MAX_VALUE)
          .setDoNotRetry(Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND)
          .build();

  /**
   * Returns result of a workflow instance execution or throws an exception if workflow did not
   * complete successfully.
   *
   * @param workflowType is optional.
   * @throws TimeoutException if workflow didn't complete within specified timeout
   * @throws CancellationException if workflow was cancelled
   * @throws WorkflowExecutionFailedException if workflow execution failed
   * @throws WorkflowTimedOutException if workflow execution exceeded its execution timeout and was
   *     forcefully terminated by the Temporal server.
   * @throws WorkflowTerminatedException if workflow execution was terminated through an external
   *     terminate command.
   */
  public static byte[] getWorkflowExecutionResult(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit)
      throws TimeoutException, CancellationException, WorkflowExecutionFailedException,
          WorkflowTerminatedException, WorkflowTimedOutException {
    HistoryEvent closeEvent =
        getInstanceCloseEvent(service, domain, workflowExecution, timeout, unit);
    return getResultFromCloseEvent(workflowExecution, workflowType, closeEvent);
  }

  public static CompletableFuture<byte[]> getWorkflowExecutionResultAsync(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit) {
    return getInstanceCloseEventAsync(service, domain, workflowExecution, timeout, unit)
        .thenApply(
            (closeEvent) -> getResultFromCloseEvent(workflowExecution, workflowType, closeEvent));
  }

  private static byte[] getResultFromCloseEvent(
      WorkflowExecution workflowExecution, Optional<String> workflowType, HistoryEvent closeEvent) {
    if (closeEvent == null) {
      throw new IllegalStateException("Workflow is still running");
    }
    switch (closeEvent.getEventType()) {
      case EventTypeWorkflowExecutionCompleted:
        return closeEvent.getWorkflowExecutionCompletedEventAttributes().getResult().toByteArray();
      case EventTypeWorkflowExecutionCanceled:
        byte[] details =
            closeEvent.getWorkflowExecutionCanceledEventAttributes().getDetails().toByteArray();
        String message = details != null ? new String(details, UTF_8) : null;
        throw new CancellationException(message);
      case EventTypeWorkflowExecutionFailed:
        WorkflowExecutionFailedEventAttributes failed =
            closeEvent.getWorkflowExecutionFailedEventAttributes();
        throw new WorkflowExecutionFailedException(
            failed.getReason(),
            failed.getDetails().toByteArray(),
            failed.getDecisionTaskCompletedEventId());
      case EventTypeWorkflowExecutionTerminated:
        WorkflowExecutionTerminatedEventAttributes terminated =
            closeEvent.getWorkflowExecutionTerminatedEventAttributes();
        throw new WorkflowTerminatedException(
            workflowExecution,
            workflowType,
            terminated.getReason(),
            terminated.getIdentity(),
            terminated.getDetails().toByteArray());
      case EventTypeWorkflowExecutionTimedOut:
        WorkflowExecutionTimedOutEventAttributes timedOut =
            closeEvent.getWorkflowExecutionTimedOutEventAttributes();
        throw new WorkflowTimedOutException(
            workflowExecution, workflowType, timedOut.getTimeoutType());
      default:
        throw new RuntimeException(
            "Workflow end state is not completed: " + prettyPrintHistoryEvent(closeEvent));
    }
  }

  /** Returns an instance closing event, potentially waiting for workflow to complete. */
  public static HistoryEvent getInstanceCloseEvent(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    ByteString pageToken = null;
    GetWorkflowExecutionHistoryResponse response;
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    HistoryEvent event;
    do {
      GetWorkflowExecutionHistoryRequest r =
          GetWorkflowExecutionHistoryRequest.newBuilder()
              .setDomain(domain)
              .setExecution(workflowExecution)
              .setHistoryEventFilterType(HistoryEventFilterType.HistoryEventFilterTypeCloseEvent)
              .setNextPageToken(pageToken == null ? ByteString.EMPTY : pageToken)
              .setWaitForNewEvent(true)
              .build();
      try {
        response =
            Retryer.retryWithResult(
                retryParameters, () -> service.blockingStub().getWorkflowExecutionHistory(r));
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          throw e;
        } else {
          throw CheckedExceptionWrapper.wrap(e);
        }
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
      if (history != null && history.getEventsCount() > 0) {
        event = history.getEvents(0);
        if (!isWorkflowExecutionCompletedEvent(event)) {
          throw new RuntimeException("Last history event is not completion event: " + event);
        }
        // Workflow called continueAsNew. Start polling the new generation with new runId.
        if (event.getEventType() == EventType.EventTypeWorkflowExecutionContinuedAsNew) {
          pageToken = null;
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
      GrpcWorkflowServiceFactory service,
      String domain,
      final WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit) {
    return getInstanceCloseEventAsync(service, domain, workflowExecution, null, timeout, unit);
  }

  private static CompletableFuture<HistoryEvent> getInstanceCloseEventAsync(
      GrpcWorkflowServiceFactory service,
      String domain,
      final WorkflowExecution workflowExecution,
      ByteString pageToken,
      long timeout,
      TimeUnit unit) {
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setDomain(domain)
            .setExecution(workflowExecution)
            .setHistoryEventFilterType(HistoryEventFilterType.HistoryEventFilterTypeCloseEvent)
            .setNextPageToken(pageToken)
            .build();
    CompletableFuture<GetWorkflowExecutionHistoryResponse> response =
        getWorkflowExecutionHistoryAsync(service, request);
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
          if (history == null || history.getEventsCount() == 0) {
            // Empty poll returned
            return getInstanceCloseEventAsync(
                service, domain, workflowExecution, pageToken, timeout, unit);
          }
          HistoryEvent event = history.getEvents(0);
          if (!isWorkflowExecutionCompletedEvent(event)) {
            throw new RuntimeException("Last history event is not completion event: " + event);
          }
          // Workflow called continueAsNew. Start polling the new generation with new runId.
          if (event.getEventType() == EventType.EventTypeWorkflowExecutionContinuedAsNew) {
            WorkflowExecution nextWorkflowExecution =
                WorkflowExecution.newBuilder()
                    .setWorkflowId(workflowExecution.getWorkflowId())
                    .setRunId(
                        event
                            .getWorkflowExecutionContinuedAsNewEventAttributes()
                            .getNewExecutionRunId())
                    .build();
            return getInstanceCloseEventAsync(
                service, domain, nextWorkflowExecution, r.getNextPageToken(), timeout, unit);
          }
          return CompletableFuture.completedFuture(event);
        });
  }

  private static CompletableFuture<GetWorkflowExecutionHistoryResponse>
      getWorkflowExecutionHistoryAsync(
          GrpcWorkflowServiceFactory service, GetWorkflowExecutionHistoryRequest r) {
    return Retryer.retryWithResultAsync(
        retryParameters,
        () -> {
          return ListenableFuturesExtra.toCompletableFuture(
              service.futureStub().getWorkflowExecutionHistory(r));
        });
  }

  public static boolean isWorkflowExecutionCompletedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EventTypeWorkflowExecutionCompleted
            || event.getEventType() == EventType.EventTypeWorkflowExecutionCanceled
            || event.getEventType() == EventType.EventTypeWorkflowExecutionFailed
            || event.getEventType() == EventType.EventTypeWorkflowExecutionTimedOut
            || event.getEventType() == EventType.EventTypeWorkflowExecutionContinuedAsNew
            || event.getEventType() == EventType.EventTypeWorkflowExecutionTerminated));
  }

  public static boolean isWorkflowExecutionCompleteDecision(Decision decision) {
    return ((decision != null)
        && (decision.getDecisionType() == DecisionType.DecisionTypeCompleteWorkflowExecution
            || decision.getDecisionType() == DecisionType.DecisionTypeCancelWorkflowExecution
            || decision.getDecisionType() == DecisionType.DecisionTypeFailWorkflowExecution
            || decision.getDecisionType()
                == DecisionType.DecisionTypeContinueAsNewWorkflowExecution));
  }

  public static boolean isActivityTaskClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EventTypeActivityTaskCompleted
            || event.getEventType() == EventType.EventTypeActivityTaskCanceled
            || event.getEventType() == EventType.EventTypeActivityTaskFailed
            || event.getEventType() == EventType.EventTypeActivityTaskTimedOut));
  }

  public static boolean isExternalWorkflowClosedEvent(HistoryEvent event) {
    return ((event != null)
        && (event.getEventType() == EventType.EventTypeChildWorkflowExecutionCompleted
            || event.getEventType() == EventType.EventTypeChildWorkflowExecutionCanceled
            || event.getEventType() == EventType.EventTypeChildWorkflowExecutionFailed
            || event.getEventType() == EventType.EventTypeChildWorkflowExecutionTerminated
            || event.getEventType() == EventType.EventTypeChildWorkflowExecutionTimedOut));
  }

  public static WorkflowExecution getWorkflowIdFromExternalWorkflowCompletedEvent(
      HistoryEvent event) {
    if (event != null) {
      if (event.getEventType() == EventType.EventTypeChildWorkflowExecutionCompleted) {
        return event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EventTypeChildWorkflowExecutionCanceled) {
        return event.getChildWorkflowExecutionCanceledEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EventTypeChildWorkflowExecutionFailed) {
        return event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EventTypeChildWorkflowExecutionTerminated) {
        return event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution();
      } else if (event.getEventType() == EventType.EventTypeChildWorkflowExecutionTimedOut) {
        return event.getChildWorkflowExecutionTimedOutEventAttributes().getWorkflowExecution();
      }
    }

    return null;
  }

  public static String getId(HistoryEvent historyEvent) {
    String id = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType() == EventType.EventTypeStartChildWorkflowExecutionFailed) {
        id = historyEvent.getStartChildWorkflowExecutionFailedEventAttributes().getWorkflowId();
      }
    }

    return id;
  }

  public static String getFailureCause(HistoryEvent historyEvent) {
    String failureCause = null;
    if (historyEvent != null) {
      if (historyEvent.getEventType() == EventType.EventTypeStartChildWorkflowExecutionFailed) {
        failureCause =
            historyEvent
                .getStartChildWorkflowExecutionFailedEventAttributes()
                .getCause()
                .toString();
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
   * @param workflowExecution result of {@link
   *     WorkflowServiceGrpc.WorkflowServiceBlockingStub#startWorkflowExecution(StartWorkflowExecutionRequest)}
   * @return instance close status
   */
  public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletion(
      GrpcWorkflowServiceFactory service, String domain, WorkflowExecution workflowExecution) {
    try {
      return waitForWorkflowInstanceCompletion(
          service, domain, workflowExecution, 0, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new Error("should never happen", e);
    }
  }

  /**
   * Waits up to specified timeout for workflow instance completion. <strong>Never</strong> use in
   * production setting as polling for worklow instance status is an expensive operation.
   *
   * @param workflowExecution result of {@link
   *     WorkflowServiceGrpc.WorkflowServiceBlockingStub#startWorkflowExecution(StartWorkflowExecutionRequest)}
   * @param timeout maximum time to wait for completion. 0 means wait forever.
   * @return instance close status
   */
  public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletion(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    HistoryEvent closeEvent =
        getInstanceCloseEvent(service, domain, workflowExecution, timeout, unit);
    return getCloseStatus(closeEvent);
  }

  public static WorkflowExecutionCloseStatus getCloseStatus(HistoryEvent event) {
    switch (event.getEventType()) {
      case EventTypeWorkflowExecutionCanceled:
        return WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusCanceled;
      case EventTypeWorkflowExecutionFailed:
        return WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusFailed;
      case EventTypeWorkflowExecutionTimedOut:
        return WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusTimedOut;
      case EventTypeWorkflowExecutionContinuedAsNew:
        return WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusContinuedAsNew;
      case EventTypeWorkflowExecutionCompleted:
        return WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusCompleted;
      case EventTypeWorkflowExecutionTerminated:
        return WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusTerminated;
      default:
        throw new IllegalArgumentException("Not close event: " + event);
    }
  }

  /**
   * Like {@link #waitForWorkflowInstanceCompletion(GrpcWorkflowServiceFactory, String,
   * WorkflowExecution, long, TimeUnit)} , except will wait for continued generations of the
   * original workflow execution too.
   *
   * @see #waitForWorkflowInstanceCompletion(GrpcWorkflowServiceFactory, String, WorkflowExecution,
   *     long, TimeUnit)
   */
  public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletionAcrossGenerations(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {

    WorkflowExecution lastExecutionToRun = workflowExecution;
    long millisecondsAtFirstWait = System.currentTimeMillis();
    WorkflowExecutionCloseStatus lastExecutionToRunCloseStatus =
        waitForWorkflowInstanceCompletion(service, domain, lastExecutionToRun, timeout, unit);

    // keep waiting if the instance continued as new
    while (lastExecutionToRunCloseStatus
        == WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusContinuedAsNew) {
      // get the new execution's information
      HistoryEvent closeEvent =
          getInstanceCloseEvent(service, domain, lastExecutionToRun, timeout, unit);
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
              domain,
              newGenerationExecution,
              timeoutInSecondsForNextWait,
              TimeUnit.MILLISECONDS);
      lastExecutionToRun = newGenerationExecution;
    }

    return lastExecutionToRunCloseStatus;
  }

  /**
   * Like {@link #waitForWorkflowInstanceCompletion(GrpcWorkflowServiceFactory, String,
   * WorkflowExecution, long, TimeUnit)} , but with no timeout.*
   */
  public static WorkflowExecutionCloseStatus waitForWorkflowInstanceCompletionAcrossGenerations(
      GrpcWorkflowServiceFactory service, String domain, WorkflowExecution workflowExecution)
      throws InterruptedException {
    try {
      return waitForWorkflowInstanceCompletionAcrossGenerations(
          service, domain, workflowExecution, 0L, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new Error("should never happen", e);
    }
  }

  public static WorkflowExecutionInfo describeWorkflowInstance(
      GrpcWorkflowServiceFactory service, String domain, WorkflowExecution workflowExecution) {
    DescribeWorkflowExecutionRequest describeRequest =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setDomain(domain)
            .setExecution(workflowExecution)
            .build();
    DescribeWorkflowExecutionResponse executionDetail = null;
    try {
      executionDetail = service.blockingStub().describeWorkflowExecution(describeRequest);
    } catch (StatusRuntimeException e) {
      throw new RuntimeException(e);
    }
    WorkflowExecutionInfo instanceMetadata = executionDetail.getWorkflowExecutionInfo();
    return instanceMetadata;
  }

  public static GetWorkflowExecutionHistoryResponse getHistoryPage(
      ByteString nextPageToken,
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution) {

    GetWorkflowExecutionHistoryRequest getHistoryRequest =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setDomain(domain)
            .setExecution(workflowExecution)
            .setNextPageToken(nextPageToken)
            .build();

    GetWorkflowExecutionHistoryResponse history;
    try {
      history = service.blockingStub().getWorkflowExecutionHistory(getHistoryRequest);
    } catch (StatusRuntimeException e) {
      throw new Error(e);
    }
    if (history == null) {
      throw new IllegalArgumentException("unknown workflow execution: " + workflowExecution);
    }
    return history;
  }

  /** Returns workflow instance history in a human readable format. */
  public static String prettyPrintHistory(
      GrpcWorkflowServiceFactory service, String domain, WorkflowExecution workflowExecution) {
    return prettyPrintHistory(service, domain, workflowExecution, true);
  }
  /**
   * Returns workflow instance history in a human readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (decider events) are not
   *     included
   */
  public static String prettyPrintHistory(
      GrpcWorkflowServiceFactory service,
      String domain,
      WorkflowExecution workflowExecution,
      boolean showWorkflowTasks) {
    Iterator<HistoryEvent> events = getHistory(service, domain, workflowExecution);
    return prettyPrintHistory(events, showWorkflowTasks);
  }

  public static Iterator<HistoryEvent> getHistory(
      GrpcWorkflowServiceFactory service, String domain, WorkflowExecution workflowExecution) {
    return new Iterator<HistoryEvent>() {
      ByteString nextPageToken;
      Iterator<HistoryEvent> current;

      {
        getNextPage();
      }

      @Override
      public boolean hasNext() {
        return current.hasNext() || nextPageToken != null;
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
            getHistoryPage(nextPageToken, service, domain, workflowExecution);
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
    result.append("{");
    boolean first = true;
    long firstTimestamp = 0;
    while (events.hasNext()) {
      HistoryEvent event = events.next();
      if (!showWorkflowTasks && event.getEventType().toString().startsWith("WorkflowTask")) {
        continue;
      }
      if (first) {
        first = false;
        firstTimestamp = event.getTimestamp();
      } else {
        result.append(",");
      }
      result.append("\n");
      result.append(INDENTATION);
      result.append(prettyPrintHistoryEvent(event, firstTimestamp));
    }
    result.append("\n}");
    return result.toString();
  }

  public static String prettyPrintDecisions(Iterable<Decision> decisions) {
    StringBuilder result = new StringBuilder();
    result.append("{");
    boolean first = true;
    for (Decision decision : decisions) {
      if (first) {
        first = false;
      } else {
        result.append(",");
      }
      result.append("\n");
      result.append(INDENTATION);
      result.append(prettyPrintDecision(decision));
    }
    result.append("\n}");
    return result.toString();
  }

  /**
   * Returns single event in a human readable format
   *
   * @param event event to pretty print
   */
  public static String prettyPrintHistoryEvent(HistoryEvent event) {
    return prettyPrintHistoryEvent(event, -1);
  }

  private static String prettyPrintHistoryEvent(HistoryEvent event, long firstTimestamp) {
    String eventType = event.getEventType().toString();
    StringBuilder result = new StringBuilder();
    result.append(event.getEventId());
    result.append(": ");
    result.append(eventType);
    if (firstTimestamp > 0) {
      // timestamp is in nanos
      long timestamp = (event.getTimestamp() - firstTimestamp) / 1_000_000;
      result.append(String.format(" [%s ms]", timestamp));
    }
    result.append(" ");
    result.append(
        prettyPrintObject(
            getEventAttributes(event), "getFieldValue", true, INDENTATION, false, false));

    return result.toString();
  }

  private static Object getEventAttributes(HistoryEvent event) {
    try {
      Method m = HistoryEvent.class.getMethod("get" + event.getEventType() + "EventAttributes");
      return m.invoke(event);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      return event;
    }
  }

  /**
   * Returns single decision in a human readable format
   *
   * @param decision decision to pretty print
   */
  public static String prettyPrintDecision(Decision decision) {
    return prettyPrintObject(decision, "getFieldValue", true, INDENTATION, true, true);
  }

  /**
   * Not really a generic method for printing random object graphs. But it works for events and
   * decisions.
   */
  private static String prettyPrintObject(
      Object object,
      String methodToSkip,
      boolean skipNullsAndEmptyCollections,
      String indentation,
      boolean skipLevel,
      boolean printTypeName) {
    StringBuilder result = new StringBuilder();
    if (object == null) {
      return "null";
    }
    Class<? extends Object> clz = object.getClass();
    if (Number.class.isAssignableFrom(clz)) {
      return String.valueOf(object);
    }
    if (Boolean.class.isAssignableFrom(clz)) {
      return String.valueOf(object);
    }
    if (clz.equals(String.class)) {
      return (String) object;
    }
    if (clz.equals(byte[].class)) {
      return new String((byte[]) object, UTF_8);
    }
    if (ByteBuffer.class.isAssignableFrom(clz)) {
      ByteBuffer buf = (ByteBuffer) object;
      byte[] arr = new byte[buf.remaining()];
      return new String(arr, UTF_8);
    }
    if (clz.equals(Date.class)) {
      return String.valueOf(object);
    }
    if (clz.equals(TaskList.class)) {
      return String.valueOf(((TaskList) object).getName());
    }
    if (clz.equals(ActivityType.class)) {
      return String.valueOf(((ActivityType) object).getName());
    }
    if (clz.equals(WorkflowType.class)) {
      return String.valueOf(((WorkflowType) object).getName());
    }
    if (Map.Entry.class.isAssignableFrom(clz)) {
      result.append(
          prettyPrintObject(
              ((Map.Entry) object).getKey(),
              methodToSkip,
              skipNullsAndEmptyCollections,
              "",
              skipLevel,
              printTypeName));
      result.append("=");
      result.append(
          prettyPrintObject(
              ((Map.Entry) object).getValue(),
              methodToSkip,
              skipNullsAndEmptyCollections,
              "",
              skipLevel,
              printTypeName));
      return result.toString();
    }
    if (Map.class.isAssignableFrom(clz)) {
      result.append("{ ");

      String prefix = "";
      for (Object entry : ((Map) object).entrySet()) {
        result.append(prefix);
        prefix = ", ";
        result.append(
            prettyPrintObject(
                entry, methodToSkip, skipNullsAndEmptyCollections, "", skipLevel, printTypeName));
      }

      result.append(" }");
      return result.toString();
    }
    if (Collection.class.isAssignableFrom(clz)) {
      return String.valueOf(object);
    }
    if (!skipLevel) {
      if (printTypeName) {
        result.append(object.getClass().getSimpleName());
        result.append(" ");
      }
      result.append("{");
    }
    Method[] eventMethods = object.getClass().getDeclaredMethods();
    boolean first = true;
    for (Method method : eventMethods) {
      String name = method.getName();
      if (!name.startsWith("get")
          || name.equals("getDecisionType")
          || method.getParameterCount() != 0
          || !Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      if (name.equals(methodToSkip) || name.equals("getClass")) {
        continue;
      }
      if (Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      Object value;
      try {
        value = method.invoke(object, (Object[]) null);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e.getTargetException());
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      if (skipNullsAndEmptyCollections) {
        if (value == null) {
          continue;
        }
        if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
          continue;
        }
        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
          continue;
        }
      }
      if (!skipLevel) {
        if (first) {
          first = false;
        } else {
          result.append(";");
        }
        result.append("\n");
        result.append(indentation);
        result.append(INDENTATION);
        result.append(name.substring(3));
        result.append(" = ");
        // Pretty print JSON serialized exceptions.
        if (name.equals("getDetails") && value instanceof byte[]) {
          String details = new String((byte[]) value, UTF_8);
          details = prettyPrintJson(details, INDENTATION + INDENTATION);
          // GSON pretty prints, but doesn't let to set an initial indentation.
          // Thus indenting the pretty printed JSON through regexp :(.
          String replacement = "\n" + indentation + INDENTATION;
          details = details.replaceAll("\\n|\\\\n", replacement);
          result.append(details);
          continue;
        }
        result.append(
            prettyPrintObject(
                value,
                methodToSkip,
                skipNullsAndEmptyCollections,
                indentation + INDENTATION,
                false,
                false));
      } else {
        result.append(
            prettyPrintObject(
                value,
                methodToSkip,
                skipNullsAndEmptyCollections,
                indentation,
                false,
                printTypeName));
      }
    }
    if (!skipLevel) {
      result.append("\n");
      result.append(indentation);
      result.append("}");
    }
    return result.toString();
  }

  public static boolean containsEvent(List<HistoryEvent> history, EventType eventType) {
    for (HistoryEvent event : history) {
      if (event.getEventType() == eventType) {
        return true;
      }
    }
    return false;
  }

  /**
   * Pretty prints JSON. Not a generic utility. Used to prettify Details fields that contain
   * serialized exceptions.
   */
  private static String prettyPrintJson(String jsonValue, String stackIndentation) {
    try {
      JsonObject json = JsonParser.parseString(jsonValue).getAsJsonObject();
      fixStackTrace(json, stackIndentation);
      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      return gson.toJson(json);
    } catch (Exception e) {
      return jsonValue;
    }
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
            && (eventType == EventType.EventTypeActivityTaskScheduled
                || eventType == EventType.EventTypeStartChildWorkflowExecutionInitiated
                || eventType == EventType.EventTypeTimerStarted
                || eventType == EventType.EventTypeWorkflowExecutionCompleted
                || eventType == EventType.EventTypeWorkflowExecutionFailed
                || eventType == EventType.EventTypeWorkflowExecutionCanceled
                || eventType == EventType.EventTypeWorkflowExecutionContinuedAsNew
                || eventType == EventType.EventTypeActivityTaskCancelRequested
                || eventType == EventType.EventTypeRequestCancelActivityTaskFailed
                || eventType == EventType.EventTypeTimerCanceled
                || eventType == EventType.EventTypeCancelTimerFailed
                || eventType == EventType.EventTypeRequestCancelExternalWorkflowExecutionInitiated
                || eventType == EventType.EventTypeMarkerRecorded
                || eventType == EventType.EventTypeSignalExternalWorkflowExecutionInitiated
                || eventType == EventType.EventTypeUpsertWorkflowSearchAttributes));
    return result;
  }

  public static EventType getEventTypeForDecision(DecisionType decisionType) {
    switch (decisionType) {
      case DecisionTypeScheduleActivityTask:
        return EventType.EventTypeActivityTaskScheduled;
      case DecisionTypeRequestCancelActivityTask:
        return EventType.EventTypeActivityTaskCancelRequested;
      case DecisionTypeStartTimer:
        return EventType.EventTypeTimerStarted;
      case DecisionTypeCompleteWorkflowExecution:
        return EventType.EventTypeWorkflowExecutionCompleted;
      case DecisionTypeFailWorkflowExecution:
        return EventType.EventTypeWorkflowExecutionFailed;
      case DecisionTypeCancelTimer:
        return EventType.EventTypeTimerCanceled;
      case DecisionTypeCancelWorkflowExecution:
        return EventType.EventTypeWorkflowExecutionCanceled;
      case DecisionTypeRequestCancelExternalWorkflowExecution:
        return EventType.EventTypeExternalWorkflowExecutionCancelRequested;
      case DecisionTypeRecordMarker:
        return EventType.EventTypeMarkerRecorded;
      case DecisionTypeContinueAsNewWorkflowExecution:
        return EventType.EventTypeWorkflowExecutionContinuedAsNew;
      case DecisionTypeStartChildWorkflowExecution:
        return EventType.EventTypeStartChildWorkflowExecutionInitiated;
      case DecisionTypeSignalExternalWorkflowExecution:
        return EventType.EventTypeSignalExternalWorkflowExecutionInitiated;
      case DecisionTypeUpsertWorkflowSearchAttributes:
        return EventType.EventTypeUpsertWorkflowSearchAttributes;
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
