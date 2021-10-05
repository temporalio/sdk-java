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

import com.google.protobuf.util.Timestamps;
import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Optional;

final class ReplayWorkflowExecutor {

  private final ReplayWorkflow workflow;

  private final Scope metricsScope;

  private final WorkflowStateMachines workflowStateMachines;

  private final ReplayWorkflowContextImpl context;

  private boolean completed;

  private WorkflowExecutionException failure;

  private boolean cancelRequested;

  public ReplayWorkflowExecutor(
      ReplayWorkflow workflow,
      Scope metricsScope,
      WorkflowStateMachines workflowStateMachines,
      ReplayWorkflowContextImpl context) {
    this.workflow = workflow;
    this.metricsScope = metricsScope;
    this.workflowStateMachines = workflowStateMachines;
    this.context = context;
  }

  public boolean isCompleted() {
    return completed;
  }

  public void eventLoop() {
    if (completed) {
      return;
    }
    try {
      completed = workflow.eventLoop();
    } catch (WorkflowExecutionException e) {
      failure = e;
      completed = true;
    } catch (CanceledFailure e) {
      if (!cancelRequested) {
        failure = workflow.mapUnexpectedException(e);
      }
      completed = true;
    }
    if (completed) {
      completeWorkflow();
    }
  }

  private void completeWorkflow() {
    if (cancelRequested) {
      workflowStateMachines.cancelWorkflow();
      if (!workflowStateMachines.isReplaying()) {
        metricsScope.counter(MetricsType.WORKFLOW_CANCELED_COUNTER).inc(1);
      }
    } else if (failure != null) {
      workflowStateMachines.failWorkflow(failure.getFailure());
      if (!workflowStateMachines.isReplaying()) {
        metricsScope.counter(MetricsType.WORKFLOW_FAILED_COUNTER).inc(1);
      }
    } else {
      ContinueAsNewWorkflowExecutionCommandAttributes attributes =
          context.getContinueAsNewOnCompletion();
      if (attributes != null) {
        workflowStateMachines.continueAsNewWorkflow(attributes);
        if (!workflowStateMachines.isReplaying()) {
          metricsScope.counter(MetricsType.WORKFLOW_CONTINUE_AS_NEW_COUNTER).inc(1);
        }
      } else {
        Optional<Payloads> workflowOutput = workflow.getOutput();
        workflowStateMachines.completeWorkflow(workflowOutput);
        if (!workflowStateMachines.isReplaying()) {
          metricsScope.counter(MetricsType.WORKFLOW_COMPLETED_COUNTER).inc(1);
        }
      }
    }

    com.uber.m3.util.Duration d =
        ProtobufTimeUtils.toM3Duration(
            Timestamps.fromMillis(System.currentTimeMillis()),
            Timestamps.fromMillis(context.getRunStartedTimestampMillis()));
    metricsScope.timer(MetricsType.WORKFLOW_E2E_LATENCY).record(d);
  }

  public void handleWorkflowExecutionCancelRequested(HistoryEvent event) {
    WorkflowExecutionCancelRequestedEventAttributes attributes =
        event.getWorkflowExecutionCancelRequestedEventAttributes();
    context.setCancelRequested(true);
    String cause = attributes.getCause();
    workflow.cancel(cause);
    cancelRequested = true;
  }

  public void handleWorkflowExecutionSignaled(HistoryEvent event) {
    WorkflowExecutionSignaledEventAttributes signalAttributes =
        event.getWorkflowExecutionSignaledEventAttributes();
    if (completed) {
      throw new IllegalStateException("Signal received after workflow is closed.");
    }
    Optional<Payloads> input =
        signalAttributes.hasInput() ? Optional.of(signalAttributes.getInput()) : Optional.empty();
    this.workflow.handleSignal(signalAttributes.getSignalName(), input, event.getEventId());
  }

  public Optional<Payloads> query(WorkflowQuery query) {
    return workflow.query(query);
  }

  public WorkflowImplementationOptions getWorkflowImplementationOptions() {
    return workflow.getWorkflowImplementationOptions();
  }

  public void close() {
    workflow.close();
  }

  public void start(HistoryEvent startWorkflowEvent) {
    workflow.start(startWorkflowEvent, context);
  }

  public WorkflowExecutionException mapUnexpectedException(Throwable exception) {
    return workflow.mapUnexpectedException(exception);
  }
}
