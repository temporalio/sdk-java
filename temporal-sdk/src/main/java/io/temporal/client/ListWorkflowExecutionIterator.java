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
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ListWorkflowExecutionIterator
    extends EagerPaginator<ListWorkflowExecutionsResponse, WorkflowExecutionInfo> {
  private final @Nullable String query;
  private final @Nonnull String namespace;
  private final @Nullable Integer pageSize;
  private final @Nonnull GenericWorkflowClient genericClient;

  ListWorkflowExecutionIterator(
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
