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

import static io.temporal.serviceclient.MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.grpc.Deadline;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.history.v1.*;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.WorkflowExecutionFailedException;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubLongPollRpcRetryOptions;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** This class encapsulates sync long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollHelper {
  /**
   * Returns result of a workflow instance execution or throws an exception if workflow did not
   * complete successfully. Will wait for continue-as-new executions of the original workflow
   * execution if present.
   *
   * @param workflowType is optional.
   * @param metricsScope metrics with NAMESPACE tag populated
   * @throws TimeoutException if workflow didn't complete within specified timeout
   * @throws CanceledFailure if workflow was canceled
   * @throws WorkflowExecutionFailedException if workflow execution failed
   */
  static Optional<Payloads> getWorkflowExecutionResult(
      WorkflowServiceStubs service,
      RootWorkflowClientHelper workflowClientHelper,
      WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      Scope metricsScope,
      DataConverter converter,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    // getInstanceCloseEvent waits for workflow completion including new runs.
    HistoryEvent closeEvent =
        getInstanceCloseEvent(
            service, workflowClientHelper, workflowExecution, metricsScope, timeout, unit);
    return WorkflowExecutionUtils.getResultFromCloseEvent(
        workflowExecution, workflowType, closeEvent, converter);
  }

  /**
   * Waits up to specified timeout for workflow instance completion.
   *
   * @param workflowExecution workflowId and optional runId
   * @param metricsScope to use when reporting service calls
   * @param timeout maximum time to wait for completion. 0 means wait forever.
   * @return instance close status
   */
  static WorkflowExecutionStatus waitForWorkflowInstanceCompletion(
      WorkflowServiceStubs service,
      RootWorkflowClientHelper workflowClientHelper,
      WorkflowExecution workflowExecution,
      Scope metricsScope,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    HistoryEvent closeEvent =
        getInstanceCloseEvent(
            service, workflowClientHelper, workflowExecution, metricsScope, timeout, unit);
    return WorkflowExecutionUtils.getCloseStatus(closeEvent);
  }

  /**
   * @param timeout timeout to retrieve InstanceCloseEvent in {@code unit} units. If 0 - MAX_INTEGER
   *     will be used
   * @param unit time unit of {@code timeout}
   * @return an instance closing event, potentially waiting for workflow or continue-as-new
   *     executions to complete.
   */
  private static HistoryEvent getInstanceCloseEvent(
      WorkflowServiceStubs service,
      RootWorkflowClientHelper workflowClientHelper,
      WorkflowExecution workflowExecution,
      Scope metricsScope,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    ByteString pageToken = ByteString.EMPTY;
    GetWorkflowExecutionHistoryResponse response;
    // TODO: Interrupt service long poll call on timeout and on interrupt
    long start = System.currentTimeMillis();
    do {
      GetWorkflowExecutionHistoryRequest request =
          workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);
      long elapsed = System.currentTimeMillis() - start;
      long millisRemaining = unit.toMillis(timeout != 0 ? timeout : Integer.MAX_VALUE) - elapsed;

      if (millisRemaining > 0) {
        RpcRetryOptions retryOptions =
            DefaultStubLongPollRpcRetryOptions.getBuilder()
                .setExpiration(Duration.ofMillis(millisRemaining))
                .build();
        response =
            GrpcRetryer.retryWithResult(
                retryOptions,
                () -> {
                  long elapsedInRetry = System.currentTimeMillis() - start;
                  Deadline expirationInRetry =
                      Deadline.after(
                          unit.toMillis(timeout) - elapsedInRetry, TimeUnit.MILLISECONDS);
                  return service
                      .blockingStub()
                      .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                      .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                      .withDeadline(expirationInRetry)
                      .getWorkflowExecutionHistory(request);
                });
        if (response == null || !response.hasHistory()) {
          continue;
        }
      } else {
        throw new TimeoutException(
            "WorkflowId="
                + workflowExecution.getWorkflowId()
                + ", runId="
                + workflowExecution.getRunId()
                + ", timeout="
                + timeout
                + ", unit="
                + unit);
      }

      pageToken = response.getNextPageToken();
      History history = response.getHistory();
      if (history.getEventsCount() > 0) {
        HistoryEvent event = history.getEvents(0);
        if (!WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
          throw new RuntimeException("Last history event is not completion event: " + event);
        }
        // Workflow called continueAsNew. Start polling the new execution with new runId.
        if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) {
          pageToken = ByteString.EMPTY;
          workflowExecution =
              WorkflowExecution.newBuilder()
                  .setWorkflowId(workflowExecution.getWorkflowId())
                  .setRunId(
                      event
                          .getWorkflowExecutionContinuedAsNewEventAttributes()
                          .getNewExecutionRunId())
                  .build();
          continue;
        }
        return event;
      }
    } while (true);
  }
}
