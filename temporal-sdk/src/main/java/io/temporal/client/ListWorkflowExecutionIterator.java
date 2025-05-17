package io.temporal.client;

import com.google.protobuf.ByteString;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ListWorkflowExecutionIterator
    extends EagerPaginator<ListWorkflowExecutionsResponse, WorkflowExecutionInfo> {
  private final @Nullable String query;
  private final @Nonnull String namespace;
  private final @Nullable Integer pageSize;
  private final @Nonnull GenericWorkflowClient genericClient;

  public ListWorkflowExecutionIterator(
      @Nullable String query,
      @Nonnull String namespace,
      @Nullable Integer pageSize,
      @Nonnull GenericWorkflowClient genericClient) {
    this.query = query;
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.pageSize = pageSize;
    this.genericClient = Objects.requireNonNull(genericClient, "genericClient");
  }

  @Override
  CompletableFuture<ListWorkflowExecutionsResponse> performRequest(
      @Nonnull ByteString nextPageToken) {
    ListWorkflowExecutionsRequest.Builder request =
        ListWorkflowExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setNextPageToken(nextPageToken);

    if (query != null) {
      request.setQuery(query);
    }

    if (pageSize != null) {
      request.setPageSize(pageSize);
    }

    return genericClient.listWorkflowExecutionsAsync(request.build());
  }

  @Override
  ByteString getNextPageToken(ListWorkflowExecutionsResponse response) {
    return response.getNextPageToken();
  }

  @Override
  List<WorkflowExecutionInfo> toElements(ListWorkflowExecutionsResponse response) {
    return response.getExecutionsList();
  }
}
