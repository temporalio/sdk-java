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

import com.google.common.util.concurrent.ListenableFuture;
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
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubLongPollRpcRetryOptions;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

/** This class encapsulates async long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollAsyncHelper {

  static CompletableFuture<Optional<Payloads>> getWorkflowExecutionResultAsync(
      WorkflowServiceStubs service,
      RootWorkflowClientHelper workflowClientHelper,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      long timeout,
      TimeUnit unit,
      DataConverter converter) {
    return getInstanceCloseEventAsync(
            service, workflowClientHelper, workflowExecution, ByteString.EMPTY, timeout, unit)
        .thenApply(
            (closeEvent) ->
                getResultFromCloseEvent(workflowExecution, workflowType, closeEvent, converter));
  }

  /** Returns an instance closing event, potentially waiting for workflow to complete. */
  private static CompletableFuture<HistoryEvent> getInstanceCloseEventAsync(
      WorkflowServiceStubs service,
      RootWorkflowClientHelper workflowClientHelper,
      final WorkflowExecution workflowExecution,
      ByteString pageToken,
      long timeout,
      TimeUnit unit) {
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    GetWorkflowExecutionHistoryRequest request =
        workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);
    CompletableFuture<GetWorkflowExecutionHistoryResponse> response =
        getWorkflowExecutionHistoryAsync(service, request, timeout, unit);
    return response.thenComposeAsync(
        (r) -> {
          if (timeout != 0 && System.currentTimeMillis() - start > unit.toMillis(timeout)) {
            throw CheckedExceptionWrapper.wrap(
                new TimeoutException(
                    "WorkflowId="
                        + workflowExecution.getWorkflowId()
                        + ", runId="
                        + workflowExecution.getRunId()
                        + ", timeout="
                        + timeout
                        + ", unit="
                        + unit));
          }
          History history = r.getHistory();
          if (history.getEventsCount() == 0) {
            // Empty poll returned
            return getInstanceCloseEventAsync(
                service, workflowClientHelper, workflowExecution, pageToken, timeout, unit);
          }
          HistoryEvent event = history.getEvents(0);
          if (!WorkflowExecutionUtils.isWorkflowExecutionCompletedEvent(event)) {
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
                service,
                workflowClientHelper,
                nextWorkflowExecution,
                r.getNextPageToken(),
                timeout,
                unit);
          }
          return CompletableFuture.completedFuture(event);
        });
  }

  private static CompletableFuture<GetWorkflowExecutionHistoryResponse>
      getWorkflowExecutionHistoryAsync(
          WorkflowServiceStubs service,
          GetWorkflowExecutionHistoryRequest r,
          long timeout,
          TimeUnit unit) {
    long start = System.currentTimeMillis();

    RpcRetryOptions retryOptions =
        DefaultStubLongPollRpcRetryOptions.getBuilder()
            .setExpiration(Duration.ofMillis(unit.toMillis(timeout)))
            .build();

    return GrpcRetryer.retryWithResultAsync(
        retryOptions,
        () -> {
          CompletableFuture<GetWorkflowExecutionHistoryResponse> result = new CompletableFuture<>();
          long elapsedInRetry = System.currentTimeMillis() - start;
          Deadline expirationInRetry =
              Deadline.after(unit.toMillis(timeout) - elapsedInRetry, TimeUnit.MILLISECONDS);
          ListenableFuture<GetWorkflowExecutionHistoryResponse> resultFuture =
              service.futureStub().withDeadline(expirationInRetry).getWorkflowExecutionHistory(r);
          resultFuture.addListener(
              () -> {
                try {
                  result.complete(resultFuture.get());
                } catch (ExecutionException e) {
                  result.completeExceptionally(e.getCause());
                } catch (Exception e) {
                  result.completeExceptionally(e);
                }
              },
              ForkJoinPool.commonPool());
          return result;
        });
  }
}
