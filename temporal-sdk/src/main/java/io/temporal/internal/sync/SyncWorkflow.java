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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.client.WorkflowClient;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.replay.WorkflowContext;
import io.temporal.internal.statemachines.UpdateProtocolCallback;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.UpdateInfo;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * SyncWorkflow supports workflows that use synchronous blocking code. An instance is created per
 * cached workflow run.
 */
class SyncWorkflow implements ReplayWorkflow {

  private static final Logger log = LoggerFactory.getLogger(SyncWorkflow.class);

  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final SyncWorkflowDefinition workflow;
  @Nonnull private final WorkflowImplementationOptions workflowImplementationOptions;
  private final WorkflowExecutorCache cache;
  private final long defaultDeadlockDetectionTimeout;
  private final WorkflowMethodThreadNameStrategy workflowMethodThreadNameStrategy =
      ExecutionInfoStrategy.INSTANCE;
  private final SyncWorkflowContext workflowContext;
  private WorkflowExecutionHandler workflowProc;
  private DeterministicRunner runner;
  private DataConverter dataConverter;
  private DataConverter dataConverterWithWorkflowContext;

  public SyncWorkflow(
      String namespace,
      WorkflowExecution workflowExecution,
      SyncWorkflowDefinition workflow,
      SignalDispatcher signalDispatcher,
      QueryDispatcher queryDispatcher,
      UpdateDispatcher updateDispatcher,
      @Nullable WorkflowImplementationOptions workflowImplementationOptions,
      DataConverter dataConverter,
      WorkflowThreadExecutor workflowThreadExecutor,
      WorkflowExecutorCache cache,
      List<ContextPropagator> contextPropagators,
      long defaultDeadlockDetectionTimeout) {
    this.workflow = Objects.requireNonNull(workflow);
    this.workflowImplementationOptions =
        workflowImplementationOptions == null
            ? WorkflowImplementationOptions.getDefaultInstance()
            : workflowImplementationOptions;
    this.workflowThreadExecutor = Objects.requireNonNull(workflowThreadExecutor);
    this.cache = cache;
    this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
    this.dataConverter = dataConverter;
    this.dataConverterWithWorkflowContext =
        dataConverter.withContext(
            new WorkflowSerializationContext(namespace, workflowExecution.getWorkflowId()));
    this.workflowContext =
        new SyncWorkflowContext(
            namespace,
            workflowExecution,
            workflow,
            signalDispatcher,
            queryDispatcher,
            updateDispatcher,
            workflowImplementationOptions,
            dataConverter,
            contextPropagators);
  }

  @Override
  public void start(HistoryEvent event, ReplayWorkflowContext context) {
    if (event.getEventType() != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
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

    this.workflowContext.setReplayContext(context);

    workflowProc =
        new WorkflowExecutionHandler(
            workflowContext, workflow, startEvent, workflowImplementationOptions);
    // The following order is ensured by this code and DeterministicRunner implementation:
    // 1. workflow.initialize
    // 2. signal handler (if signalWithStart was called)
    // 3. main workflow method
    runner =
        DeterministicRunner.newRunner(
            workflowThreadExecutor,
            workflowContext,
            () -> {
              workflowProc.runConstructor();
              WorkflowInternal.newWorkflowMethodThread(
                      () -> workflowProc.runWorkflowMethod(),
                      workflowMethodThreadNameStrategy.createThreadName(
                          context.getWorkflowExecution()))
                  .start();
            },
            cache);
  }

  @Override
  public void handleSignal(
      String signalName, Optional<Payloads> input, long eventId, Header header) {
    // Signals can trigger completion
    runner.executeInWorkflowThread(
        "signal " + signalName,
        () -> {
          workflowProc.handleSignal(signalName, input, eventId, header);
        });
  }

  @Override
  public void handleUpdate(
      String updateName,
      String updateId,
      Optional<Payloads> input,
      long eventId,
      Header header,
      UpdateProtocolCallback callbacks) {
    final UpdateInfo updateInfo = new UpdateInfoImpl(updateName, updateId);
    runner.executeInWorkflowThread(
        "update " + updateName,
        () -> {
          try {
            workflowContext.setCurrentUpdateInfo(updateInfo);
            MDC.put(LoggerTag.UPDATE_ID, updateInfo.getUpdateId());
            MDC.put(LoggerTag.UPDATE_NAME, updateInfo.getUpdateName());
            // Skip validator on replay
            if (!callbacks.isReplaying()) {
              try {
                workflowContext.setReadOnly(true);
                workflowProc.handleValidateUpdate(updateName, updateId, input, eventId, header);
              } catch (ReadOnlyException r) {
                // Rethrow instead on rejecting the update to fail the WFT
                throw r;
              } catch (Exception e) {
                callbacks.reject(
                    workflowContext
                        .getDataConverterWithCurrentWorkflowContext()
                        .exceptionToFailure(e));
                return;
              } finally {
                workflowContext.setReadOnly(false);
              }
            }
            callbacks.accept();
            try {
              Optional<Payloads> result =
                  workflowProc.handleExecuteUpdate(updateName, updateId, input, eventId, header);
              callbacks.complete(result, null);
            } catch (WorkflowExecutionException e) {
              callbacks.complete(Optional.empty(), e.getFailure());
            }
          } finally {
            workflowContext.setCurrentUpdateInfo(null);
          }
        });
  }

  @Override
  public boolean eventLoop() {
    if (runner == null) {
      return false;
    }
    runner.runUntilAllBlocked(defaultDeadlockDetectionTimeout);
    return runner.isDone() || workflowProc.isDone(); // Do not wait for all other threads.
  }

  @Override
  public Optional<Payloads> getOutput() {
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
  public Optional<Payloads> query(WorkflowQuery query) {
    if (WorkflowClient.QUERY_TYPE_REPLAY_ONLY.equals(query.getQueryType())) {
      return Optional.empty();
    }
    if (WorkflowClient.QUERY_TYPE_STACK_TRACE.equals(query.getQueryType())) {
      // stack trace query result should be readable for UI even if user specifies a custom data
      // converter
      return DefaultDataConverter.STANDARD_INSTANCE.toPayloads(runner.stackTrace());
    }
    if (WorkflowClient.QUERY_TYPE_WORKFLOW_METADATA.equals(query.getQueryType())) {
      return dataConverterWithWorkflowContext.toPayloads(workflowContext.getWorkflowMetadata());
    }
    Optional<Payloads> args =
        query.hasQueryArgs() ? Optional.of(query.getQueryArgs()) : Optional.empty();
    return workflowProc.handleQuery(query.getQueryType(), query.getHeader(), args);
  }

  @Override
  public WorkflowContext getWorkflowContext() {
    return workflowContext;
  }
}
