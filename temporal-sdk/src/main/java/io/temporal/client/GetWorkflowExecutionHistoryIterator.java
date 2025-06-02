package io.temporal.client;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.internal.client.EagerPaginator;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class GetWorkflowExecutionHistoryIterator
    extends EagerPaginator<GetWorkflowExecutionHistoryResponse, HistoryEvent> {
  private final @Nonnull String namespace;
  private final @Nonnull WorkflowExecution workflowExecution;
  private final Integer pageSize;
  private final @Nonnull GenericWorkflowClient genericClient;

  public GetWorkflowExecutionHistoryIterator(
      @Nonnull String namespace,
      @Nonnull WorkflowExecution workflowExecution,
      @Nullable Integer pageSize,
      @Nonnull GenericWorkflowClient genericClient) {
    this.namespace = namespace;
    this.workflowExecution = workflowExecution;
    this.pageSize = pageSize;
    this.genericClient = genericClient;
  }

  @Override
  protected CompletableFuture<GetWorkflowExecutionHistoryResponse> performRequest(
      @Nonnull ByteString nextPageToken) {
    GetWorkflowExecutionHistoryRequest.Builder requestBuilder =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(workflowExecution)
            .setNextPageToken(nextPageToken);

    if (pageSize != null) {
      requestBuilder.setMaximumPageSize(pageSize);
    }

    return genericClient.getWorkflowExecutionHistoryAsync(requestBuilder.build());
  }

  @Override
  protected ByteString getNextPageToken(GetWorkflowExecutionHistoryResponse response) {
    return response.getNextPageToken();
  }

  @Override
  protected List<HistoryEvent> toElements(GetWorkflowExecutionHistoryResponse response) {
    return response.getHistory().getEventsList();
  }
}
