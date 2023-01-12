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
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
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
  CompletableFuture<GetWorkflowExecutionHistoryResponse> performRequest(
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
  ByteString getNextPageToken(GetWorkflowExecutionHistoryResponse response) {
    return response.getNextPageToken();
  }

  @Override
  List<HistoryEvent> toElements(GetWorkflowExecutionHistoryResponse response) {
    return response.getHistory().getEventsList();
  }
}
