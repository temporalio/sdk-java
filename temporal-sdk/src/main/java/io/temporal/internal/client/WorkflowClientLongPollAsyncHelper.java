package io.temporal.internal.client;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.concurrent.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** This class encapsulates async long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollAsyncHelper {

  /**
   * Returns the history response whose single event closed the workflow, potentially waiting for
   * workflow or continue-as-new executions to complete. It carries the Nexus serialization context
   * of the closed run, if the server returned one on any page of that run. Completes exceptionally
   * with a {@link TimeoutException} if the workflow didn't complete within the specified timeout.
   */
  static CompletableFuture<GetWorkflowExecutionHistoryResponse> getInstanceCloseEventAsync(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      @Nonnull WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit) {
    Deadline longPollTimeoutDeadline = Deadline.after(timeout, unit);
    return getInstanceCloseEventAsync(
            genericClient,
            workflowClientHelper,
            workflowExecution,
            ByteString.EMPTY,
            null,
            longPollTimeoutDeadline)
        .handle(
            (closed, e) -> {
              if (e == null) {
                return closed;
              } else {
                throw handleException(e, longPollTimeoutDeadline, workflowExecution, timeout, unit);
              }
            });
  }

  private static CompletionException handleException(
      Throwable e,
      Deadline longPollTimeoutDeadline,
      @Nonnull WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit) {
    if (e instanceof CompletionException) {
      Throwable cause = e.getCause();
      if (longPollTimeoutDeadline.isExpired()
          && cause instanceof StatusRuntimeException
          && Status.Code.DEADLINE_EXCEEDED.equals(
              ((StatusRuntimeException) cause).getStatus().getCode())) {
        // we want to form timeout exception only if the original deadline is indeed expired.
        // Otherwise, we should rethrow a raw DEADLINE_EXCEEDED. throwing TimeoutException
        // in this case will be highly misleading.
        return new CompletionException(
            WorkflowClientLongPollHelper.newTimeoutException(workflowExecution, timeout, unit));
      } else {
        return (CompletionException) e;
      }
    } else {
      return new CompletionException(e);
    }
  }

  /**
   * Returns an instance closing event, potentially waiting for workflow to complete.
   *
   * @param propagated the Nexus serialization context returned so far for the current run, carried
   *     across empty long polls of the same run.
   */
  private static CompletableFuture<GetWorkflowExecutionHistoryResponse> getInstanceCloseEventAsync(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      final WorkflowExecution workflowExecution,
      ByteString pageToken,
      @Nullable PropagatedSerializationContext propagated,
      Deadline longPollTimeoutDeadline) {
    GetWorkflowExecutionHistoryRequest request =
        workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);
    CompletableFuture<GetWorkflowExecutionHistoryResponse> response =
        genericClient.longPollHistoryAsync(request, longPollTimeoutDeadline);
    return response.thenComposeAsync(
        (r) -> {
          PropagatedSerializationContext runPropagated =
              r.hasPropagatedNexusSerializationContext()
                  ? r.getPropagatedNexusSerializationContext()
                  : propagated;
          History history = r.getHistory();
          if (history.getEventsCount() == 0) {
            // Empty poll returned
            ByteString nextPageToken =
                r.getNextPageToken().isEmpty() ? pageToken : r.getNextPageToken();
            return getInstanceCloseEventAsync(
                genericClient,
                workflowClientHelper,
                workflowExecution,
                nextPageToken,
                runPropagated,
                longPollTimeoutDeadline);
          }
          HistoryEvent event = history.getEvents(0); // should be only one event
          if (!WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
            throw new RuntimeException("Unexpected workflow execution closing event: " + event);
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
                genericClient,
                workflowClientHelper,
                nextWorkflowExecution,
                ByteString.EMPTY,
                null,
                longPollTimeoutDeadline);
          }
          return CompletableFuture.completedFuture(
              WorkflowClientLongPollHelper.withPropagatedNexusSerializationContext(
                  r, runPropagated));
        });
  }
}
