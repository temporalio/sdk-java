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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ListWorkflowExecutionIterator implements Iterator<WorkflowExecutionInfo> {
  private static final Logger log = LoggerFactory.getLogger(ListWorkflowExecutionIterator.class);

  private final @Nonnull String query;
  private final @Nonnull String namespace;
  private final int pageSize;
  private final @Nonnull GenericWorkflowClient genericClient;
  private ListWorkflowExecutionsResponse activeResponse;
  private int nextActiveResponseIndex;
  private CompletableFuture<ListWorkflowExecutionsResponse> nextResponse;

  ListWorkflowExecutionIterator(
      @Nonnull String query,
      @Nonnull String namespace,
      int pageSize,
      @Nonnull GenericWorkflowClient genericClient) {
    this.query = Objects.requireNonNull(query, "query");
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.pageSize = pageSize;
    this.genericClient = Objects.requireNonNull(genericClient, "genericClient");
  }

  @Override
  public boolean hasNext() {
    if (nextActiveResponseIndex < activeResponse.getExecutionsCount()) {
      return true;
    }
    fetch();
    return nextActiveResponseIndex < activeResponse.getExecutionsCount();
  }

  @Override
  public WorkflowExecutionInfo next() {
    if (hasNext()) {
      return activeResponse.getExecutions(nextActiveResponseIndex++);
    } else {
      throw new NoSuchElementException();
    }
  }

  void fetch() {
    if (nextResponse == null) {
      // if nextResponse is null, it's the end of the iteration through the pages
      return;
    }

    ListWorkflowExecutionsResponse response;
    try {
      response = this.nextResponse.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e.getCause());
    }

    ByteString nextPageToken = response.getNextPageToken();
    if (!nextPageToken.isEmpty()) {
      ListWorkflowExecutionsRequest request =
          ListWorkflowExecutionsRequest.newBuilder()
              .setNamespace(namespace)
              .setQuery(query)
              .setPageSize(pageSize)
              .setNextPageToken(nextPageToken)
              .build();
      this.nextResponse = genericClient.listWorkflowExecutionsAsync(request);
    } else {
      this.nextResponse = null;
    }

    if (response.getExecutionsCount() == 0 && nextResponse != null) {
      log.warn(
          "[BUG] listWorkflowExecutions received an empty collection with a non-empty nextPageToken");
      // shouldn't be happening, but we want to tolerate it if it does, so we just effectively
      // skip the empty response and wait for the next one in a blocking manner.
      // If this actually ever happens as a normal scenario, this skipping should be reworked to
      // be done asynchronously on a completion of nextResponse future.
      fetch();
      return;
    }

    activeResponse = response;
    nextActiveResponseIndex = 0;
  }

  public void init() {
    ListWorkflowExecutionsRequest request =
        ListWorkflowExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setQuery(query)
            .setPageSize(pageSize)
            .build();
    nextResponse = new CompletableFuture<>();
    nextResponse.complete(genericClient.listWorkflowExecutions(request));
    fetch();
  }
}
