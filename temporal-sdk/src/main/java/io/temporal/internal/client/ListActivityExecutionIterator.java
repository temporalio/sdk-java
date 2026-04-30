package io.temporal.internal.client;

import com.google.protobuf.ByteString;
import io.temporal.api.activity.v1.ActivityExecutionListInfo;
import io.temporal.api.workflowservice.v1.ListActivityExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListActivityExecutionsResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ListActivityExecutionIterator
    extends EagerPaginator<ListActivityExecutionsResponse, ActivityExecutionListInfo> {

  private final @Nullable String query;
  private final @Nonnull String namespace;
  private final @Nonnull GenericWorkflowClient genericClient;

  ListActivityExecutionIterator(
      @Nullable String query,
      @Nonnull String namespace,
      @Nonnull GenericWorkflowClient genericClient) {
    this.query = query;
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.genericClient = Objects.requireNonNull(genericClient, "genericClient");
  }

  @Override
  protected CompletableFuture<ListActivityExecutionsResponse> performRequest(
      @Nonnull ByteString nextPageToken) {
    ListActivityExecutionsRequest.Builder request =
        ListActivityExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setNextPageToken(nextPageToken);

    if (query != null) {
      request.setQuery(query);
    }

    return genericClient.listActivitiesAsync(request.build());
  }

  @Override
  protected ByteString getNextPageToken(ListActivityExecutionsResponse response) {
    return response.getNextPageToken();
  }

  @Override
  protected List<ActivityExecutionListInfo> toElements(ListActivityExecutionsResponse response) {
    return response.getExecutionsList();
  }
}
