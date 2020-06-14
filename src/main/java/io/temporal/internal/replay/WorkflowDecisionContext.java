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

import static io.temporal.internal.common.DataConverterUtils.toHeaderGrpc;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.RetryParameters;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.ParentClosePolicy;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.RetryStatus;
import io.temporal.proto.common.TimeoutType;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.decision.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.event.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.proto.event.ExternalWorkflowExecutionSignaledEventAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.SignalExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.tasklist.TaskList;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.SignalExternalWorkflowException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class WorkflowDecisionContext {

  private final class ChildWorkflowCancellationHandler implements Consumer<Exception> {

    private final long initiatedEventId;
    private final String workflowId;
    private final ChildWorkflowCancellationType cancellationType;

    private ChildWorkflowCancellationHandler(
        long initiatedEventId, String workflowId, ChildWorkflowCancellationType cancellationType) {
      this.initiatedEventId = initiatedEventId;
      this.workflowId = Objects.requireNonNull(workflowId);
      this.cancellationType = cancellationType;
    }

    @Override
    public void accept(Exception cause) {
      OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.get(initiatedEventId);

      if (scheduled == null) {
        // Cancellation handlers are not deregistered. So they fire after a child completion.
        return;
      }
      switch (cancellationType) {
        case WAIT_CANCELLATION_REQUESTED:
        case WAIT_CANCELLATION_COMPLETED:
        case TRY_CANCEL:
          RequestCancelExternalWorkflowExecutionDecisionAttributes cancelAttributes =
              RequestCancelExternalWorkflowExecutionDecisionAttributes.newBuilder()
                  .setWorkflowId(workflowId)
                  .setChildWorkflowOnly(true)
                  .build();
          long cancellationInitiatedEventId =
              decisions.requestCancelExternalWorkflowExecution(cancelAttributes);
          scheduledExternalCancellations.put(cancellationInitiatedEventId, initiatedEventId);
      }
      switch (cancellationType) {
        case ABANDON:
        case TRY_CANCEL:
          scheduledExternalWorkflows.remove(initiatedEventId);
          CanceledFailure e = new CanceledFailure("Canceled without waiting", null);
          BiConsumer<Optional<Payloads>, Exception> completionCallback =
              scheduled.getCompletionCallback();
          completionCallback.accept(Optional.empty(), e);
      }
    }
  }

  private final DecisionsHelper decisions;

  private final WorkflowContext workflowContext;

  // key is initiatedEventId
  private final Map<Long, OpenChildWorkflowRequestInfo> scheduledExternalWorkflows =
      new HashMap<>();
  /** Maps cancellationInitiatedEventId to child initiatedEventId */
  private final Map<Long, Long> scheduledExternalCancellations = new HashMap<>();

  // key is initiatedEventId
  private final Map<Long, OpenRequestInfo<Void, Void>> scheduledSignals = new HashMap<>();

  WorkflowDecisionContext(DecisionsHelper decisions, WorkflowContext workflowContext) {
    this.decisions = decisions;
    this.workflowContext = workflowContext;
  }

  Consumer<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Consumer<WorkflowExecution> executionCallback,
      BiConsumer<Optional<Payloads>, Exception> callback) {
    final StartChildWorkflowExecutionDecisionAttributes.Builder attributes =
        StartChildWorkflowExecutionDecisionAttributes.newBuilder()
            .setWorkflowType(parameters.getWorkflowType());
    String workflowId = parameters.getWorkflowId();
    if (workflowId == null) {
      workflowId = randomUUID().toString();
    }
    attributes.setWorkflowId(workflowId);
    attributes.setNamespace(OptionsUtils.safeGet(parameters.getNamespace()));
    if (parameters.getInput() != null) {
      attributes.setInput(parameters.getInput());
    }
    if (parameters.getWorkflowRunTimeoutSeconds() > 0) {
      attributes.setWorkflowRunTimeoutSeconds((int) parameters.getWorkflowRunTimeoutSeconds());
    }
    if (parameters.getWorkflowExecutionTimeoutSeconds() > 0) {
      attributes.setWorkflowExecutionTimeoutSeconds(
          (int) parameters.getWorkflowExecutionTimeoutSeconds());
    }
    if (parameters.getWorkflowTaskTimeoutSeconds() > 0) {
      attributes.setWorkflowTaskTimeoutSeconds((int) parameters.getWorkflowTaskTimeoutSeconds());
    }
    String taskList = parameters.getTaskList();
    TaskList.Builder tl = TaskList.newBuilder();
    if (taskList != null && !taskList.isEmpty()) {
      tl.setName(taskList);
    } else {
      tl.setName(workflowContext.getTaskList());
    }
    attributes.setTaskList(tl);
    if (parameters.getWorkflowIdReusePolicy() != null) {
      attributes.setWorkflowIdReusePolicy(parameters.getWorkflowIdReusePolicy());
    }
    RetryParameters retryParameters = parameters.getRetryParameters();
    if (retryParameters != null) {
      attributes.setRetryPolicy(retryParameters.toRetryPolicy());
    }

    attributes.setCronSchedule(OptionsUtils.safeGet(parameters.getCronSchedule()));
    Header header = toHeaderGrpc(parameters.getContext());
    if (header != null) {
      attributes.setHeader(header);
    }

    ParentClosePolicy parentClosePolicy = parameters.getParentClosePolicy();
    if (parentClosePolicy != null) {
      attributes.setParentClosePolicy(parentClosePolicy);
    }

    long initiatedEventId = decisions.startChildWorkflowExecution(attributes.build());
    final OpenChildWorkflowRequestInfo context =
        new OpenChildWorkflowRequestInfo(parameters.getCancellationType(), executionCallback);
    context.setCompletionHandle(callback);
    scheduledExternalWorkflows.put(initiatedEventId, context);
    return new ChildWorkflowCancellationHandler(
        initiatedEventId, attributes.getWorkflowId(), parameters.getCancellationType());
  }

  Consumer<Exception> signalWorkflowExecution(
      final SignalExternalWorkflowParameters parameters, BiConsumer<Void, Exception> callback) {
    final OpenRequestInfo<Void, Void> context = new OpenRequestInfo<>();
    final SignalExternalWorkflowExecutionDecisionAttributes.Builder attributes =
        SignalExternalWorkflowExecutionDecisionAttributes.newBuilder()
            .setNamespace(OptionsUtils.safeGet(parameters.getNamespace()));
    String signalId = decisions.getAndIncrementNextId();
    attributes.setControl(signalId);
    attributes.setSignalName(parameters.getSignalName());
    Optional<Payloads> input = parameters.getInput();
    if (input.isPresent()) {
      attributes.setInput(input.get());
    }
    attributes.setExecution(
        WorkflowExecution.newBuilder()
            .setRunId(OptionsUtils.safeGet(parameters.getRunId()))
            .setWorkflowId(parameters.getWorkflowId()));
    final long finalSignalId = decisions.signalExternalWorkflowExecution(attributes.build());
    context.setCompletionHandle(callback);
    scheduledSignals.put(finalSignalId, context);
    return (e) -> {
      if (!scheduledSignals.containsKey(finalSignalId)) {
        // Cancellation handlers are not deregistered. So they fire after a signal completion.
        return;
      }
      decisions.cancelSignalExternalWorkflowExecution(finalSignalId, null);
      OpenRequestInfo<Void, Void> scheduled = scheduledSignals.remove(finalSignalId);
      if (scheduled == null) {
        throw new IllegalArgumentException("Signal \"" + finalSignalId + "\" wasn't scheduled");
      }
      callback.accept(null, e);
    };
  }

  void requestCancelWorkflowExecution(WorkflowExecution execution) {
    RequestCancelExternalWorkflowExecutionDecisionAttributes attributes =
        RequestCancelExternalWorkflowExecutionDecisionAttributes.newBuilder()
            .setWorkflowId(execution.getWorkflowId())
            .setRunId(execution.getRunId())
            .build();
    decisions.requestCancelExternalWorkflowExecution(attributes);
  }

  void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters continueParameters) {

    // TODO: add validation to check if continueAsNew is not set
    workflowContext.setContinueAsNewOnCompletion(continueParameters);
  }

  /** Replay safe UUID */
  UUID randomUUID() {
    String runId = workflowContext.getCurrentRunId();
    if (runId == null) {
      throw new Error("null currentRunId");
    }
    String id = runId + ":" + decisions.getAndIncrementNextId();
    byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
    return UUID.nameUUIDFromBytes(bytes);
  }

  Random newRandom() {
    return new Random(randomUUID().getLeastSignificantBits());
  }

  void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {
    ExternalWorkflowExecutionCancelRequestedEventAttributes attributes =
        event.getExternalWorkflowExecutionCancelRequestedEventAttributes();
    decisions.handleExternalWorkflowExecutionCancelRequested(event);
    Long initiatedEventId = scheduledExternalCancellations.remove(attributes.getInitiatedEventId());
    if (initiatedEventId == null) {
      return;
    }
    OpenChildWorkflowRequestInfo scheduled = scheduledExternalWorkflows.get(initiatedEventId);
    if (scheduled == null) {
      return;
    }
    if (scheduled.getCancellationType()
        == ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED) {
      scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      CanceledFailure e = new CanceledFailure("Child workflow cancellation requested");
      BiConsumer<Optional<Payloads>, Exception> completionCallback =
          scheduled.getCompletionCallback();
      completionCallback.accept(Optional.empty(), e);
    }
  }

  void handleChildWorkflowExecutionCanceled(HistoryEvent event) {
    ChildWorkflowExecutionCanceledEventAttributes attributes =
        event.getChildWorkflowExecutionCanceledEventAttributes();
    if (decisions.handleChildWorkflowExecutionCanceled(attributes)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        // TODO(maxim): Add support for passing details without using converter here
        CanceledFailure e = new CanceledFailure("Child canceled");
        BiConsumer<Optional<Payloads>, Exception> completionCallback =
            scheduled.getCompletionCallback();
        completionCallback.accept(Optional.empty(), e);
      }
    }
  }

  void handleChildWorkflowExecutionStarted(HistoryEvent event) {
    ChildWorkflowExecutionStartedEventAttributes attributes =
        event.getChildWorkflowExecutionStartedEventAttributes();
    decisions.handleChildWorkflowExecutionStarted(event);
    OpenChildWorkflowRequestInfo scheduled =
        scheduledExternalWorkflows.get(attributes.getInitiatedEventId());
    if (scheduled != null) {
      scheduled.getExecutionCallback().accept(attributes.getWorkflowExecution());
    }
  }

  void handleChildWorkflowExecutionTimedOut(HistoryEvent event) {
    ChildWorkflowExecutionTimedOutEventAttributes attributes =
        event.getChildWorkflowExecutionTimedOutEventAttributes();
    if (decisions.handleChildWorkflowExecutionTimedOut(attributes)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        TimeoutFailure timeoutFailure = new TimeoutFailure(null, null, TimeoutType.StartToClose);
        timeoutFailure.setStackTrace(new StackTraceElement[0]);
        RuntimeException failure =
            new ChildWorkflowFailure(
                attributes.getInitiatedEventId(),
                attributes.getStartedEventId(),
                attributes.getWorkflowType().getName(),
                attributes.getWorkflowExecution(),
                attributes.getNamespace(),
                attributes.getRetryStatus(),
                timeoutFailure);
        BiConsumer<Optional<Payloads>, Exception> completionCallback =
            scheduled.getCompletionCallback();
        completionCallback.accept(Optional.empty(), failure);
      }
    }
  }

  void handleChildWorkflowExecutionTerminated(HistoryEvent event) {
    ChildWorkflowExecutionTerminatedEventAttributes attributes =
        event.getChildWorkflowExecutionTerminatedEventAttributes();
    WorkflowExecution execution = attributes.getWorkflowExecution();
    if (decisions.handleChildWorkflowExecutionTerminated(attributes)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        RuntimeException failure =
            new ChildWorkflowFailure(
                attributes.getInitiatedEventId(),
                attributes.getStartedEventId(),
                attributes.getWorkflowType().getName(),
                attributes.getWorkflowExecution(),
                attributes.getNamespace(),
                RetryStatus.NonRetryableFailure,
                new TerminatedFailure(null, null));
        BiConsumer<Optional<Payloads>, Exception> completionCallback =
            scheduled.getCompletionCallback();
        completionCallback.accept(Optional.empty(), failure);
      }
    }
  }

  void handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
    StartChildWorkflowExecutionFailedEventAttributes attributes =
        event.getStartChildWorkflowExecutionFailedEventAttributes();
    if (decisions.handleStartChildWorkflowExecutionFailed(event)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        Exception failure =
            new ChildWorkflowTaskFailedException(
                event.getEventId(),
                WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build(),
                attributes.getWorkflowType(),
                RetryStatus.NonRetryableFailure,
                null);
        failure.initCause(
            new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build(),
                attributes.getWorkflowType().getName(),
                null));
        BiConsumer<Optional<Payloads>, Exception> completionCallback =
            scheduled.getCompletionCallback();
        completionCallback.accept(Optional.empty(), failure);
      }
    }
  }

  void handleChildWorkflowExecutionFailed(HistoryEvent event) {
    ChildWorkflowExecutionFailedEventAttributes attributes =
        event.getChildWorkflowExecutionFailedEventAttributes();
    if (decisions.handleChildWorkflowExecutionFailed(attributes)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        RuntimeException failure =
            new ChildWorkflowTaskFailedException(
                event.getEventId(),
                attributes.getWorkflowExecution(),
                attributes.getWorkflowType(),
                attributes.getRetryStatus(),
                attributes.getFailure());
        BiConsumer<Optional<Payloads>, Exception> completionCallback =
            scheduled.getCompletionCallback();
        completionCallback.accept(Optional.empty(), failure);
      }
    }
  }

  void handleChildWorkflowExecutionCompleted(HistoryEvent event) {
    ChildWorkflowExecutionCompletedEventAttributes attributes =
        event.getChildWorkflowExecutionCompletedEventAttributes();
    if (decisions.handleChildWorkflowExecutionCompleted(attributes)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        BiConsumer<Optional<Payloads>, Exception> completionCallback =
            scheduled.getCompletionCallback();
        Optional<Payloads> result =
            attributes.hasResult() ? Optional.of(attributes.getResult()) : Optional.empty();
        completionCallback.accept(result, null);
      }
    }
  }

  void handleSignalExternalWorkflowExecutionFailed(HistoryEvent event) {
    SignalExternalWorkflowExecutionFailedEventAttributes attributes =
        event.getSignalExternalWorkflowExecutionFailedEventAttributes();
    long initiatedEventId = attributes.getInitiatedEventId();
    if (decisions.handleSignalExternalWorkflowExecutionFailed(initiatedEventId)) {
      OpenRequestInfo<Void, Void> signalContextAndResult =
          scheduledSignals.remove(initiatedEventId);
      if (signalContextAndResult != null) {
        WorkflowExecution signaledExecution =
            WorkflowExecution.newBuilder()
                .setWorkflowId(attributes.getWorkflowExecution().getWorkflowId())
                .setRunId(attributes.getWorkflowExecution().getRunId())
                .build();
        RuntimeException failure = new SignalExternalWorkflowException(signaledExecution, null);
        signalContextAndResult.getCompletionCallback().accept(null, failure);
      }
    }
  }

  void handleExternalWorkflowExecutionSignaled(HistoryEvent event) {
    ExternalWorkflowExecutionSignaledEventAttributes attributes =
        event.getExternalWorkflowExecutionSignaledEventAttributes();
    long initiatedEventId = attributes.getInitiatedEventId();
    if (decisions.handleExternalWorkflowExecutionSignaled(initiatedEventId)) {
      OpenRequestInfo<Void, Void> signalCtxAndResult = scheduledSignals.remove(initiatedEventId);
      if (signalCtxAndResult != null) {
        signalCtxAndResult.getCompletionCallback().accept(null, null);
      }
    }
  }
}
