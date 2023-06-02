/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

public final class ListScheduleListDescriptionIterator
    extends EagerPaginator<ListSchedulesResponse, ScheduleListEntry> {
  private final @Nonnull String namespace;
  private final @Nullable Integer pageSize;
  private final @Nonnull GenericWorkflowClient genericClient;

  public ListScheduleListDescriptionIterator(
      @Nonnull String namespace,
      @Nullable Integer pageSize,
      @Nonnull GenericWorkflowClient genericClient) {
    this.namespace = namespace;
    this.pageSize = pageSize;
    this.genericClient = genericClient;
  }

  @Override
  CompletableFuture<ListSchedulesResponse> performRequest(@Nonnull ByteString nextPageToken) {
    ListSchedulesRequest.Builder request =
        ListSchedulesRequest.newBuilder().setNamespace(namespace).setNextPageToken(nextPageToken);

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
