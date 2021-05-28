/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.replay;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.serviceclient.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Supports iteration over history while loading new pages through calls to the service. */
class WorkflowHistoryIterator implements Iterator<HistoryEvent> {

  private final Duration retryServiceOperationInitialInterval = Duration.ofMillis(200);
  private final Duration retryServiceOperationMaxInterval = Duration.ofSeconds(4);
  private final Duration paginationStart = Duration.ofMillis(System.currentTimeMillis());
  public final WorkflowServiceStubs service;
  private final Duration workflowTaskTimeout;
  private final String namespace;
  private final Scope metricsScope;
  private final PollWorkflowTaskQueueResponseOrBuilder task;
  private Iterator<HistoryEvent> current;
  ByteString nextPageToken;

  WorkflowHistoryIterator(
      WorkflowServiceStubs service,
      String namespace,
      PollWorkflowTaskQueueResponseOrBuilder task,
      Duration workflowTaskTimeout,
      Scope metricsScope) {
    this.service = service;
    this.namespace = namespace;
    this.task = task;
    this.workflowTaskTimeout = Objects.requireNonNull(workflowTaskTimeout);
    this.metricsScope = metricsScope;
    History history = task.getHistory();
    current = history.getEventsList().iterator();
    nextPageToken = task.getNextPageToken();
  }

  // Returns true if more history events are available.
  // Server can return page tokens that point to empty pages.
  // We need to verify that page is valid before returning true.
  // Otherwise next() method would throw NoSuchElementException after hasNext() returning true.
  @Override
  public boolean hasNext() {
    if (current.hasNext()) {
      return true;
    }
    if (nextPageToken.isEmpty()) {
      return false;
    }

    GetWorkflowExecutionHistoryResponse response = queryWorkflowExecutionHistory();

    current = response.getHistory().getEventsList().iterator();
    nextPageToken = response.getNextPageToken();

    return current.hasNext();
  }

  @Override
  public HistoryEvent next() {
    if (hasNext()) {
      return current.next();
    }
    throw new NoSuchElementException();
  }

  GetWorkflowExecutionHistoryResponse queryWorkflowExecutionHistory() {
    Duration passed = Duration.ofMillis(System.currentTimeMillis()).minus(paginationStart);
    Duration expiration = workflowTaskTimeout.minus(passed);
    if (expiration.isZero() || expiration.isNegative()) {
      throw Status.DEADLINE_EXCEEDED
          .withDescription(
              "getWorkflowExecutionHistory pagination took longer than workflow task timeout")
          .asRuntimeException();
    }
    RpcRetryOptions retryOptions =
        RpcRetryOptions.newBuilder()
            .setExpiration(expiration)
            .setInitialInterval(retryServiceOperationInitialInterval)
            .setMaximumInterval(retryServiceOperationMaxInterval)
            .build();
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(task.getWorkflowExecution())
            .setNextPageToken(nextPageToken)
            .build();
    try {
      return GrpcRetryer.retryWithResult(
          retryOptions,
          () ->
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                  .getWorkflowExecutionHistory(request));
    } catch (Exception e) {
      throw new Error(e);
    }
  }
}
