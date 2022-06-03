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

package io.temporal.internal.client;

import static io.temporal.internal.common.WorkflowExecutionUtils.getResultFromCloseEvent;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.Optional;
import java.util.concurrent.*;
import javax.annotation.Nonnull;

/** This class encapsulates async long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollAsyncHelper {

  static CompletableFuture<Optional<Payloads>> getWorkflowExecutionResultAsync(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      @Nonnull WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit,
      DataConverter converter) {
    Deadline longPollTimeoutDeadline = Deadline.after(timeout, unit);
    return getInstanceCloseEventAsync(
            genericClient,
            workflowClientHelper,
            workflowExecution,
            ByteString.EMPTY,
            longPollTimeoutDeadline)
        .handle(
            (closeEvent, e) -> {
              if (e == null) {
                return getResultFromCloseEvent(
                    workflowExecution, workflowType, closeEvent, converter);
              } else {
                throw handleException(e, longPollTimeoutDeadline, workflowExecution, timeout, unit);
              }
            });
  }

  private static CompletionException handleException(
      Throwable e,
      Deadline longPollTimeoutDeadline,
      @Nonnull WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit) {
    if (e instanceof CompletionException) {
      Throwable cause = e.getCause();
      if (longPollTimeoutDeadline.isExpired()
          && cause instanceof StatusRuntimeException
          && Status.Code.DEADLINE_EXCEEDED.equals(
              ((StatusRuntimeException) cause).getStatus().getCode())) {
        // we want to form timeout exception only if the original deadline is indeed expired.
        // Otherwise, we should rethrow a raw DEADLINE_EXCEEDED. throwing TimeoutException
        // in this case will be highly misleading.
        return new CompletionException(
            WorkflowClientLongPollHelper.newTimeoutException(workflowExecution, timeout, unit));
      } else {
        return (CompletionException) e;
      }
    } else {
      return new CompletionException(e);
    }
  }

  /** Returns an instance closing event, potentially waiting for workflow to complete. */
  private static CompletableFuture<HistoryEvent> getInstanceCloseEventAsync(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      final WorkflowExecution workflowExecution,
      ByteString pageToken,
      Deadline longPollTimeoutDeadline) {
    GetWorkflowExecutionHistoryRequest request =
        workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);
    CompletableFuture<GetWorkflowExecutionHistoryResponse> response =
        genericClient.longPollHistoryAsync(request, longPollTimeoutDeadline);
    return response.thenComposeAsync(
        (r) -> {
          History history = r.getHistory();
          if (history.getEventsCount() == 0) {
            // Empty poll returned
            ByteString nextPageToken =
                r.getNextPageToken().isEmpty() ? pageToken : r.getNextPageToken();
            return getInstanceCloseEventAsync(
                genericClient,
                workflowClientHelper,
                workflowExecution,
                nextPageToken,
                longPollTimeoutDeadline);
          }
          HistoryEvent event = history.getEvents(0); // should be only one event
          if (!WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
            throw new RuntimeException("Unexpected workflow execution closing event: " + event);
          }
          // Workflow called continueAsNew. Start polling the new generation with new runId.
          if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) {
            WorkflowExecution nextWorkflowExecution =
                WorkflowExecution.newBuilder()
                    .setWorkflowId(workflowExecution.getWorkflowId())
                    .setRunId(
                        event
                            .getWorkflowExecutionContinuedAsNewEventAttributes()
                            .getNewExecutionRunId())
                    .build();
            return getInstanceCloseEventAsync(
                genericClient,
                workflowClientHelper,
                nextWorkflowExecution,
                ByteString.EMPTY,
                longPollTimeoutDeadline);
          }
          return CompletableFuture.completedFuture(event);
        });
  }
}
