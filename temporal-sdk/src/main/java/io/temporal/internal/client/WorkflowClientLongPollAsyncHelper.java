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

package io.temporal.internal.client;

import static io.temporal.internal.common.WorkflowExecutionUtils.getResultFromCloseEvent;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
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
import io.temporal.serviceclient.CheckedExceptionWrapper;
import java.util.Optional;
import java.util.concurrent.*;

/** This class encapsulates async long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollAsyncHelper {

  static CompletableFuture<Optional<Payloads>> getWorkflowExecutionResultAsync(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit,
      DataConverter converter) {
    return getInstanceCloseEventAsync(
            genericClient, workflowClientHelper, workflowExecution, ByteString.EMPTY, timeout, unit)
        .thenApply(
            (closeEvent) ->
                getResultFromCloseEvent(workflowExecution, workflowType, closeEvent, converter));
  }

  /** Returns an instance closing event, potentially waiting for workflow to complete. */
  private static CompletableFuture<HistoryEvent> getInstanceCloseEventAsync(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      final WorkflowExecution workflowExecution,
      ByteString pageToken,
      long timeout,
      TimeUnit unit) {
    GetWorkflowExecutionHistoryRequest request =
        workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);
    Deadline deadline = Deadline.after(timeout, unit);
    CompletableFuture<GetWorkflowExecutionHistoryResponse> response =
        genericClient.longPollHistoryAsync(request, deadline);
    return response.thenComposeAsync(
        (r) -> {
          // TODO to fix https://github.com/temporalio/sdk-java/issues/1177 we need to process
          //  DEADLINE_EXCEEDED
          //  or an underlying TimeoutException
          if (deadline.isExpired()) {
            // TODO check that such throwing populates a stacktrace into TimeoutException. It likely
            // doesn't.
            //  Instead a CompletionException should be used
            throw CheckedExceptionWrapper.wrap(
                WorkflowClientLongPollHelper.newTimeoutException(workflowExecution, timeout, unit));
          }
          History history = r.getHistory();
          if (history.getEventsCount() == 0) {
            // Empty poll returned
            return getInstanceCloseEventAsync(
                genericClient, workflowClientHelper, workflowExecution, pageToken, timeout, unit);
          }
          HistoryEvent event = history.getEvents(0);
          if (!WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
            throw new RuntimeException("Last history event is not completion event: " + event);
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
                r.getNextPageToken(),
                timeout,
                unit);
          }
          return CompletableFuture.completedFuture(event);
        });
  }
}
