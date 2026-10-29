package io.temporal.internal.client;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.history.v1.*;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** This class encapsulates sync long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollHelper {
  private WorkflowClientLongPollHelper() {}

  /**
   * @param timeout timeout to retrieve InstanceCloseEvent in {@code unit} units. If 0 - MAX_INTEGER
   *     will be used
   * @param unit time unit of {@code timeout}
   * @return the history response whose single event closed the workflow, potentially waiting for
   *     workflow or continue-as-new executions to complete. It carries the Nexus serialization
   *     context of the closed run, if the server returned one on any page of that run.
   * @throws TimeoutException if workflow didn't complete within specified timeout
   */
  static GetWorkflowExecutionHistoryResponse getInstanceCloseEvent(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      @Nonnull WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    ByteString pageToken = ByteString.EMPTY;
    GetWorkflowExecutionHistoryResponse response;
    Deadline longPollTimeoutDeadline = Deadline.after(timeout, unit);
    // Each run of a continue-as-new chain carries its own context, so it is reset per run.
    @Nullable PropagatedSerializationContext propagatedSerializationContext = null;

    while (true) {
      GetWorkflowExecutionHistoryRequest request =
          workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);

      try {
        response = genericClient.longPollHistory(request, longPollTimeoutDeadline);
      } catch (StatusRuntimeException e) {
        if (longPollTimeoutDeadline.isExpired()
            && Status.Code.DEADLINE_EXCEEDED.equals(e.getStatus().getCode())) {
          // we want to form timeout exception only if the original deadline is indeed expired.
          // Otherwise, we should rethrow a raw DEADLINE_EXCEEDED. throwing TimeoutException
          // in this case will be highly misleading.
          throw newTimeoutException(workflowExecution, timeout, unit);
        }
        throw e;
      }
      if (response.hasPropagatedNexusSerializationContext()) {
        propagatedSerializationContext = response.getPropagatedNexusSerializationContext();
      }

      History history = response.getHistory();
      if (history.getEventsCount() > 0) {
        HistoryEvent event = history.getEvents(0); // should be only one event
        if (!WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
          throw new RuntimeException("Unexpected workflow execution closing event: " + event);
        }
        // Workflow called continueAsNew. Start polling the new execution with new runId.
        if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) {
          pageToken = ByteString.EMPTY;
          propagatedSerializationContext = null;
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
        return withPropagatedNexusSerializationContext(response, propagatedSerializationContext);
      }
      if (!response.getNextPageToken().isEmpty()) {
        pageToken = response.getNextPageToken();
      }
    }
  }

  /**
   * Sets the Nexus serialization context retained from an earlier page of the run on the response
   * that closed it, since paginated responses may omit it.
   */
  static GetWorkflowExecutionHistoryResponse withPropagatedNexusSerializationContext(
      GetWorkflowExecutionHistoryResponse response,
      @Nullable PropagatedSerializationContext propagated) {
    if (propagated == null || response.hasPropagatedNexusSerializationContext()) {
      return response;
    }
    return response.toBuilder().setPropagatedNexusSerializationContext(propagated).build();
  }

  static TimeoutException newTimeoutException(
      @Nonnull WorkflowExecution workflowExecution, long timeout, TimeUnit unit) {
    return new TimeoutException(
        "WorkflowId="
            + workflowExecution.getWorkflowId()
            + ", runId="
            + workflowExecution.getRunId()
            + ", timeout="
            + timeout
            + ", unit="
            + unit);
  }
}
