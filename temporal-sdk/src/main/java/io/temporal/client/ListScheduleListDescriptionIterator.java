package io.temporal.client;

import com.google.protobuf.ByteString;
import io.temporal.api.schedule.v1.ScheduleListEntry;
import io.temporal.api.workflowservice.v1.ListSchedulesRequest;
import io.temporal.api.workflowservice.v1.ListSchedulesResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Eager iterator for listing schedules. */
public final class ListScheduleListDescriptionIterator
    extends EagerPaginator<ListSchedulesResponse, ScheduleListEntry> {
  private final @Nonnull String namespace;
  private final @Nullable String query;
  private final @Nullable Integer pageSize;
  private final @Nonnull GenericWorkflowClient genericClient;

  public ListScheduleListDescriptionIterator(
      @Nonnull String namespace,
      @Nullable Integer pageSize,
      @Nonnull GenericWorkflowClient genericClient) {
    this.namespace = namespace;
    this.query = null;
    this.pageSize = pageSize;
    this.genericClient = genericClient;
  }

  public ListScheduleListDescriptionIterator(
      @Nonnull String namespace,
      @Nullable String query,
      @Nullable Integer pageSize,
      @Nonnull GenericWorkflowClient genericClient) {
    this.namespace = namespace;
    this.query = query;
    this.pageSize = pageSize;
    this.genericClient = genericClient;
  }

  @Override
  CompletableFuture<ListSchedulesResponse> performRequest(@Nonnull ByteString nextPageToken) {
    ListSchedulesRequest.Builder request =
        ListSchedulesRequest.newBuilder().setNamespace(namespace).setNextPageToken(nextPageToken);

    if (query != null) {
      request.setQuery(query);
    }
    if (pageSize != null) {
      request.setMaximumPageSize(pageSize);
    }
    return genericClient.listSchedulesAsync(request.build());
  }

  @Override
  ByteString getNextPageToken(ListSchedulesResponse response) {
    return response.getNextPageToken();
  }

  @Override
  List<ScheduleListEntry> toElements(ListSchedulesResponse response) {
    return response.getSchedulesList();
  }
}
