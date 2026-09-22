package io.temporal.internal.client;

import com.google.protobuf.ByteString;
import io.temporal.api.nexus.v1.NexusOperationExecutionListInfo;
import io.temporal.api.workflowservice.v1.ListNexusOperationExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListNexusOperationExecutionsResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ListNexusOperationExecutionIterator
    extends EagerPaginator<ListNexusOperationExecutionsResponse, NexusOperationExecutionListInfo> {

  private final @Nullable String query;
  private final @Nonnull String namespace;
  private final @Nonnull GenericWorkflowClient genericClient;

  ListNexusOperationExecutionIterator(
      @Nullable String query,
      @Nonnull String namespace,
      @Nonnull GenericWorkflowClient genericClient) {
    this.query = query;
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.genericClient = Objects.requireNonNull(genericClient, "genericClient");
  }

  @Override
  protected CompletableFuture<ListNexusOperationExecutionsResponse> performRequest(
      @Nonnull ByteString nextPageToken) {
    ListNexusOperationExecutionsRequest.Builder request =
        ListNexusOperationExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setNextPageToken(nextPageToken);

    if (query != null) {
      request.setQuery(query);
    }

    return genericClient.listNexusOperationExecutionsAsync(request.build());
  }

  @Override
  protected ByteString getNextPageToken(ListNexusOperationExecutionsResponse response) {
    return response.getNextPageToken();
  }

  @Override
  protected List<NexusOperationExecutionListInfo> toElements(
      ListNexusOperationExecutionsResponse response) {
    return response.getOperationsList();
  }
}
