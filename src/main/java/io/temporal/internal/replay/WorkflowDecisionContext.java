/*
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

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import io.temporal.internal.common.RetryParameters;
import io.temporal.proto.common.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.common.ExternalWorkflowExecutionSignaledEventAttributes;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.HistoryEvent;
import io.temporal.proto.common.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.SignalExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.TaskList;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.enums.ChildWorkflowExecutionFailedCause;
import io.temporal.proto.enums.ParentClosePolicy;
import io.temporal.workflow.ChildWorkflowTerminatedException;
import io.temporal.workflow.ChildWorkflowTimedOutException;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.StartChildWorkflowFailedException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class WorkflowDecisionContext {

  private final class ChildWorkflowCancellationHandler implements Consumer<Exception> {

    private final long initiatedEventId;
    private final String workflowId;

    private ChildWorkflowCancellationHandler(long initiatedEventId, String workflowId) {
      this.initiatedEventId = initiatedEventId;
      this.workflowId = Objects.requireNonNull(workflowId);
    }

    @Override
    public void accept(Exception cause) {
      if (!scheduledExternalWorkflows.containsKey(initiatedEventId)) {
        // Cancellation handlers are not deregistered. So they fire after a child completion.
        return;
      }
      RequestCancelExternalWorkflowExecutionDecisionAttributes cancelAttributes =
          RequestCancelExternalWorkflowExecutionDecisionAttributes.newBuilder()
              .setWorkflowId(workflowId)
              .setChildWorkflowOnly(true)
              .build();
      decisions.requestCancelExternalWorkflowExecution(cancelAttributes);
    }
  }

  private final DecisionsHelper decisions;

  private final WorkflowContext workflowContext;

  // key is initiatedEventId
  private final Map<Long, OpenChildWorkflowRequestInfo> scheduledExternalWorkflows =
      new HashMap<>();

  // key is initiatedEventId
  private final Map<Long, OpenRequestInfo<Void, Void>> scheduledSignals = new HashMap<>();

  WorkflowDecisionContext(DecisionsHelper decisions, WorkflowContext workflowContext) {
    this.decisions = decisions;
    this.workflowContext = workflowContext;
  }

  Consumer<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Consumer<WorkflowExecution> executionCallback,
      BiConsumer<byte[], Exception> callback) {
    final StartChildWorkflowExecutionDecisionAttributes.Builder attributes =
        StartChildWorkflowExecutionDecisionAttributes.newBuilder()
            .setWorkflowType(parameters.getWorkflowType());
    String workflowId = parameters.getWorkflowId();
    if (workflowId == null) {
      workflowId = randomUUID().toString();
    }
    attributes.setWorkflowId(workflowId);
    if (parameters.getDomain().isEmpty()) {
      // Could be removed as soon as server allows null for domain.
      attributes.setDomain(workflowContext.getDomain());
    } else {
      attributes.setDomain(parameters.getDomain());
    }
    attributes.setInput(ByteString.copyFrom(parameters.getInput()));
    if (parameters.getExecutionStartToCloseTimeoutSeconds() == 0) {
      // TODO: Substract time passed since the parent start
      attributes.setExecutionStartToCloseTimeoutSeconds(
          workflowContext.getExecutionStartToCloseTimeoutSeconds());
    } else {
      attributes.setExecutionStartToCloseTimeoutSeconds(
          (int) parameters.getExecutionStartToCloseTimeoutSeconds());
    }
    if (parameters.getTaskStartToCloseTimeoutSeconds() == 0) {
      attributes.setTaskStartToCloseTimeoutSeconds(workflowContext.getDecisionTaskTimeoutSeconds());
    } else {
      attributes.setTaskStartToCloseTimeoutSeconds(
          (int) parameters.getTaskStartToCloseTimeoutSeconds());
    }
    String taskList = parameters.getTaskList();
    TaskList.Builder tl = TaskList.newBuilder();
    if (taskList != null && !taskList.isEmpty()) {
      tl.setName(taskList);
    } else {
      tl.setName(workflowContext.getTaskList());
    }
    attributes.setTaskList(tl);
    attributes.setWorkflowIdReusePolicy(parameters.getWorkflowIdReusePolicy());
    RetryParameters retryParameters = parameters.getRetryParameters();
    if (retryParameters != null) {
      attributes.setRetryPolicy(retryParameters.toRetryPolicy());
    }

    if (!Strings.isNullOrEmpty(parameters.getCronSchedule())) {
      attributes.setCronSchedule(parameters.getCronSchedule());
    }
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
        new OpenChildWorkflowRequestInfo(executionCallback);
    context.setCompletionHandle(callback);
    scheduledExternalWorkflows.put(initiatedEventId, context);
    return new ChildWorkflowCancellationHandler(initiatedEventId, attributes.getWorkflowId());
  }

  private Header toHeaderGrpc(Map<String, byte[]> headers) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    Header.Builder headerGrpc = Header.newBuilder();
    for (Map.Entry<String, byte[]> item : headers.entrySet()) {
      headerGrpc.putFields(item.getKey(), ByteString.copyFrom(item.getValue()));
    }
    return headerGrpc.build();
  }

  boolean isChildWorkflowExecutionStartedWithRetryOptions() {
    return decisions.isChildWorkflowExecutionInitiatedWithRetryOptions();
  }

  Consumer<Exception> signalWorkflowExecution(
      final SignalExternalWorkflowParameters parameters, BiConsumer<Void, Exception> callback) {
    final OpenRequestInfo<Void, Void> context = new OpenRequestInfo<>();
    final SignalExternalWorkflowExecutionDecisionAttributes.Builder attributes =
        SignalExternalWorkflowExecutionDecisionAttributes.newBuilder();
    if (parameters.getDomain() == null) {
      attributes.setDomain(workflowContext.getDomain());
    } else {
      attributes.setDomain(parameters.getDomain());
    }
    String signalId = decisions.getAndIncrementNextId();
    attributes.setControl(ByteString.copyFrom(signalId, StandardCharsets.UTF_8));
    attributes.setSignalName(parameters.getSignalName());
    attributes.setInput(ByteString.copyFrom(parameters.getInput()));
    attributes.setExecution(
        WorkflowExecution.newBuilder()
            .setRunId(parameters.getRunId())
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
    RequestCancelExternalWorkflowExecutionDecisionAttributes.Builder attributes =
        RequestCancelExternalWorkflowExecutionDecisionAttributes.newBuilder();
    String workflowId = execution.getWorkflowId();
    attributes.setWorkflowId(workflowId);
    String runId = execution.getRunId();
    if (!runId.isEmpty()) {
      attributes.setRunId(runId);
    }
    decisions.requestCancelExternalWorkflowExecution(attributes.build());
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

  void handleChildWorkflowExecutionCanceled(HistoryEvent event) {
    ChildWorkflowExecutionCanceledEventAttributes attributes =
        event.getChildWorkflowExecutionCanceledEventAttributes();
    if (decisions.handleChildWorkflowExecutionCanceled(attributes)) {
      OpenChildWorkflowRequestInfo scheduled =
          scheduledExternalWorkflows.remove(attributes.getInitiatedEventId());
      if (scheduled != null) {
        CancellationException e = new CancellationException();
        BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, e);
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
        RuntimeException failure =
            new ChildWorkflowTimedOutException(
                event.getEventId(),
                attributes.getWorkflowExecution(),
                attributes.getWorkflowType());
        BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, failure);
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
            new ChildWorkflowTerminatedException(
                event.getEventId(), execution, attributes.getWorkflowType());
        BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, failure);
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
        WorkflowExecution workflowExecution =
            WorkflowExecution.newBuilder().setWorkflowId(attributes.getWorkflowId()).build();
        WorkflowType workflowType = attributes.getWorkflowType();
        ChildWorkflowExecutionFailedCause cause = attributes.getCause();
        RuntimeException failure =
            new StartChildWorkflowFailedException(
                event.getEventId(), workflowExecution, workflowType, cause);
        BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, failure);
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
        String reason = attributes.getReason();
        byte[] details = attributes.getDetails().toByteArray();
        RuntimeException failure =
            new ChildWorkflowTaskFailedException(
                event.getEventId(),
                attributes.getWorkflowExecution(),
                attributes.getWorkflowType(),
                reason,
                details);
        BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
        completionCallback.accept(null, failure);
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
        BiConsumer<byte[], Exception> completionCallback = scheduled.getCompletionCallback();
        byte[] result = attributes.getResult().toByteArray();
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
        RuntimeException failure =
            new SignalExternalWorkflowException(
                event.getEventId(), signaledExecution, attributes.getCause());
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
