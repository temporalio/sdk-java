package io.temporal.internal.replay;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.common.CancellationToken;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import javax.annotation.Nullable;

/** Supports iteration over history while loading new pages through calls to the service. */
class ServiceWorkflowHistoryIterator implements WorkflowHistoryIterator {

  private final Duration retryServiceOperationInitialInterval = Duration.ofMillis(200);
  private final Duration retryServiceOperationMaxInterval = Duration.ofSeconds(4);
  public final WorkflowServiceStubs service;
  private final String namespace;
  private final Scope metricsScope;
  private final PollWorkflowTaskQueueResponseOrBuilder task;
  private final GrpcRetryer grpcRetryer;
  private final @Nullable ExternalStorageRunner externalStorageRunner;
  private final CancellationToken<CancellationException> storageCancellation;
  private Deadline deadline;
  private Iterator<HistoryEvent> current;
  ByteString nextPageToken;

  ServiceWorkflowHistoryIterator(
      WorkflowServiceStubs service,
      String namespace,
      PollWorkflowTaskQueueResponseOrBuilder task,
      Scope metricsScope) {
    this(service, namespace, task, metricsScope, null, CancellationToken.none());
  }

  ServiceWorkflowHistoryIterator(
      WorkflowServiceStubs service,
      String namespace,
      PollWorkflowTaskQueueResponseOrBuilder task,
      Scope metricsScope,
      @Nullable ExternalStorageRunner externalStorageRunner,
      CancellationToken<CancellationException> storageCancellation) {
    this.storageCancellation = storageCancellation;
    this.service = service;
    this.namespace = namespace;
    this.task = task;
    this.metricsScope = metricsScope;
    this.externalStorageRunner = externalStorageRunner;
    // TODO Refactor WorkflowHistoryIteratorTest or WorkflowHistoryIterator to remove this check.
    //  `service == null` shouldn't be allowed as it's needed for a normal functioning of this
    // class.
    this.grpcRetryer = service != null ? new GrpcRetryer(service.getServerCapabilities()) : null;
    History history = task.getHistory();
    current = history.getEventsList().iterator();
    nextPageToken = task.getNextPageToken();
  }

  // Returns true if more history events are available.
  @Override
  public boolean hasNext() {
    if (current.hasNext()) {
      return true;
    }
    while (!nextPageToken.isEmpty()) {
      // Server can return page tokens that point to empty pages.
      // We need to verify that page is valid before returning true.
      // Otherwise, next() method would throw NoSuchElementException after hasNext() returning
      // true.
      GetWorkflowExecutionHistoryResponse response = queryWorkflowExecutionHistory();

      History history = response.getHistory();
      if (externalStorageRunner == null) {
        ExternalStorageRunner.throwIfContainsReference(history);
      } else {
        history = externalStorageRunner.retrieve(history, storageCancellation);
      }
      current = history.getEventsList().iterator();
      nextPageToken = response.getNextPageToken();
      // Server can return an empty page, but a valid nextPageToken that contains
      // more events.
      if (current.hasNext()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public HistoryEvent next() {
    if (hasNext()) {
      return current.next();
    }
    throw new NoSuchElementException();
  }

  public void initDeadline(Deadline deadline) {
    this.deadline = deadline;
  }

  GetWorkflowExecutionHistoryResponse queryWorkflowExecutionHistory() {
    RpcRetryOptions retryOptions =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(retryServiceOperationInitialInterval)
            .setMaximumInterval(retryServiceOperationMaxInterval)
            .validateBuildWithDefaults();
    GrpcRetryer.GrpcRetryerOptions grpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(retryOptions, deadline);
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(task.getWorkflowExecution())
            .setNextPageToken(nextPageToken)
            .build();
    try {
      return grpcRetryer.retryWithResult(
          () ->
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                  .getWorkflowExecutionHistory(request),
          grpcRetryerOptions);
    } catch (StatusRuntimeException ex) {
      if (Status.DEADLINE_EXCEEDED.equals(ex.getStatus())) {
        throw Status.DEADLINE_EXCEEDED
            .withDescription(
                "getWorkflowExecutionHistory pagination took longer than workflow task timeout")
            .withCause(ex)
            .asRuntimeException();
      }
      throw ex;
    }
  }
}
