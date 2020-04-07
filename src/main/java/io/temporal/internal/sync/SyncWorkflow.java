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

package io.temporal.internal.sync;

import io.temporal.client.WorkflowClient;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.replay.DeciderCache;
import io.temporal.internal.replay.DecisionContext;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.WorkflowExecutionStartedEventAttributes;
import io.temporal.proto.query.WorkflowQuery;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SyncWorkflow supports workflows that use synchronous blocking code. An instance is created per
 * decision.
 */
class SyncWorkflow implements ReplayWorkflow {

  private static final Logger log = LoggerFactory.getLogger(SyncWorkflow.class);

  private final DataConverter dataConverter;
  private final List<ContextPropagator> contextPropagators;
  private final ExecutorService threadPool;
  private final SyncWorkflowDefinition workflow;
  WorkflowImplementationOptions workflowImplementationOptions;
  private DeciderCache cache;
  private WorkflowExecuteRunnable workflowProc;
  private DeterministicRunner runner;

  public SyncWorkflow(
      SyncWorkflowDefinition workflow,
      WorkflowImplementationOptions workflowImplementationOptions,
      DataConverter dataConverter,
      ExecutorService threadPool,
      DeciderCache cache,
      List<ContextPropagator> contextPropagators) {
    this.workflow = Objects.requireNonNull(workflow);
    this.workflowImplementationOptions =
        workflowImplementationOptions == null
            ? new WorkflowImplementationOptions.Builder().build()
            : workflowImplementationOptions;
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.threadPool = Objects.requireNonNull(threadPool);
    this.cache = cache;
    this.contextPropagators = contextPropagators;
  }

  @Override
  public WorkflowImplementationOptions getWorkflowImplementationOptions() {
    return workflowImplementationOptions;
  }

  @Override
  public void start(HistoryEvent event, DecisionContext context) {
    if (event.getEventType() != EventType.WorkflowExecutionStarted
        || !event.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException(
          "first event is not WorkflowExecutionStarted, but " + event.getEventType());
    }

    WorkflowExecutionStartedEventAttributes startEvent =
        event.getWorkflowExecutionStartedEventAttributes();
    WorkflowType workflowType = startEvent.getWorkflowType();
    if (workflow == null) {
      throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
    }

    SyncDecisionContext syncContext =
        new SyncDecisionContext(
            context,
            dataConverter,
            contextPropagators,
            startEvent.getLastCompletionResult().toByteArray());

    workflowProc = new WorkflowExecuteRunnable(syncContext, workflow, startEvent);
    // The following order is ensured by this code and DeterministicRunner implementation:
    // 1. workflow.initialize
    // 2. signal handler (if signalWithStart was called)
    // 3. main workflow method
    runner =
        DeterministicRunner.newRunner(
            threadPool,
            syncContext,
            context::currentTimeMillis,
            () -> {
              workflow.initialize();
              WorkflowInternal.newThread(false, () -> workflowProc.run()).start();
            },
            cache);
    syncContext.setRunner(runner);
  }

  @Override
  public void handleSignal(String signalName, byte[] input, long eventId) {
    String threadName = "\"" + signalName + "\" signal handler";
    runner.executeInWorkflowThread(
        threadName, () -> workflowProc.processSignal(signalName, input, eventId));
  }

  @Override
  public boolean eventLoop() throws Throwable {
    if (runner == null) {
      return false;
    }
    workflowProc.fireTimers();
    runner.runUntilAllBlocked();
    return runner.isDone() || workflowProc.isDone(); // Do not wait for all other threads.
  }

  @Override
  public byte[] getOutput() {
    return workflowProc.getOutput();
  }

  @Override
  public void cancel(String reason) {
    runner.cancel(reason);
  }

  @Override
  public void close() {
    if (runner != null) {
      runner.close();
    }
  }

  @Override
  public long getNextWakeUpTime() {
    if (runner == null) {
      throw new IllegalStateException("Start not called");
    }
    return runner.getNextWakeUpTime();
  }

  @Override
  public byte[] query(WorkflowQuery query) {
    if (WorkflowClient.QUERY_TYPE_REPLAY_ONLY.equals(query.getQueryType())) {
      return new byte[] {};
    }
    if (WorkflowClient.QUERY_TYPE_STACK_TRACE.equals(query.getQueryType())) {
      return dataConverter.toData(runner.stackTrace());
    }
    return workflowProc.query(query.getQueryType(), query.getQueryArgs().toByteArray());
  }

  @Override
  public WorkflowExecutionException mapUnexpectedException(Exception failure) {
    return POJOWorkflowImplementationFactory.mapToWorkflowExecutionException(
        failure, dataConverter);
  }

  @Override
  public WorkflowExecutionException mapError(Error failure) {
    return POJOWorkflowImplementationFactory.mapError(failure, dataConverter);
  }
}
