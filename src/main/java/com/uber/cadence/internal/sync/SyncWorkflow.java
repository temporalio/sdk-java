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

package com.uber.cadence.internal.sync;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.context.ContextPropagator;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.internal.replay.DecisionContext;
import com.uber.cadence.internal.replay.ReplayWorkflow;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.worker.WorkflowImplementationOptions;
import com.uber.cadence.workflow.WorkflowInterceptor;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * SyncWorkflow supports workflows that use synchronous blocking code. An instance is created per
 * decision.
 */
class SyncWorkflow implements ReplayWorkflow {

  private final DataConverter dataConverter;
  private final List<ContextPropagator> contextPropagators;
  private final ExecutorService threadPool;
  private final SyncWorkflowDefinition workflow;
  WorkflowImplementationOptions workflowImplementationOptions;
  private final Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory;
  private DeciderCache cache;
  private WorkflowRunnable workflowProc;
  private DeterministicRunner runner;

  public SyncWorkflow(
      SyncWorkflowDefinition workflow,
      WorkflowImplementationOptions workflowImplementationOptions,
      DataConverter dataConverter,
      ExecutorService threadPool,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      DeciderCache cache,
      List<ContextPropagator> contextPropagators) {
    this.workflow = Objects.requireNonNull(workflow);
    this.workflowImplementationOptions =
        workflowImplementationOptions == null
            ? new WorkflowImplementationOptions.Builder().build()
            : workflowImplementationOptions;
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.threadPool = Objects.requireNonNull(threadPool);
    this.interceptorFactory = Objects.requireNonNull(interceptorFactory);
    this.cache = cache;
    this.contextPropagators = contextPropagators;
  }

  @Override
  public WorkflowImplementationOptions getWorkflowImplementationOptions() {
    return workflowImplementationOptions;
  }

  @Override
  public void start(HistoryEvent event, DecisionContext context) {
    WorkflowType workflowType =
        event.getWorkflowExecutionStartedEventAttributes().getWorkflowType();
    if (workflow == null) {
      throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
    }
    if (event.getEventType() != EventType.WorkflowExecutionStarted) {
      throw new IllegalArgumentException(
          "first event is not WorkflowExecutionStarted, but " + event.getEventType());
    }

    SyncDecisionContext syncContext =
        new SyncDecisionContext(
            context,
            dataConverter,
            contextPropagators,
            interceptorFactory,
            event.getWorkflowExecutionStartedEventAttributes().getLastCompletionResult());

    workflowProc =
        new WorkflowRunnable(
            syncContext, workflow, event.getWorkflowExecutionStartedEventAttributes());
    runner =
        DeterministicRunner.newRunner(
            threadPool, syncContext, context::currentTimeMillis, workflowProc, cache);
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
    return workflowProc.query(query.getQueryType(), query.getQueryArgs());
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
