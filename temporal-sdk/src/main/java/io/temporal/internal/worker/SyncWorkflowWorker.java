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

package io.temporal.internal.worker;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.activity.ActivityExecutionContextFactory;
import io.temporal.internal.activity.ActivityTaskHandlerImpl;
import io.temporal.internal.activity.LocalActivityExecutionContextFactoryImpl;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.internal.replay.ReplayWorkflowTaskHandler;
import io.temporal.internal.sync.POJOWorkflowImplementationFactory;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Facade that supports a lifecycle and maintains an assembly of
 *
 * <ul>
 *   <li>{@link WorkflowWorker} that performing execution of workflow task
 *   <li>{@link LocalActivityWorker} that performs execution of local activities scheduled by the
 *       workflow tasks
 * </ul>
 *
 * and exposing additional management helper methods for the assembly.
 */
public class SyncWorkflowWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(SyncWorkflowWorker.class);

  private final String identity;
  private final String namespace;
  private final String taskQueue;

  private final WorkflowWorker workflowWorker;
  private final QueryReplayHelper queryReplayHelper;
  private final LocalActivityWorker laWorker;
  private final POJOWorkflowImplementationFactory factory;
  private final DataConverter dataConverter;
  private final ActivityTaskHandlerImpl laTaskHandler;

  public SyncWorkflowWorker(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      SingleWorkerOptions singleWorkerOptions,
      SingleWorkerOptions localActivityOptions,
      @Nonnull WorkflowRunLockManager runLocks,
      @Nonnull WorkflowExecutorCache cache,
      String stickyTaskQueueName,
      WorkflowThreadExecutor workflowThreadExecutor,
      @Nonnull EagerActivityDispatcher eagerActivityDispatcher) {
    this.identity = singleWorkerOptions.getIdentity();
    this.namespace = namespace;
    this.taskQueue = taskQueue;

    this.dataConverter = singleWorkerOptions.getDataConverter();

    factory =
        new POJOWorkflowImplementationFactory(
            singleWorkerOptions,
            Objects.requireNonNull(workflowThreadExecutor),
            singleWorkerOptions.getWorkerInterceptors(),
            cache);

    ActivityExecutionContextFactory laActivityExecutionContextFactory =
        new LocalActivityExecutionContextFactoryImpl();
    laTaskHandler =
        new ActivityTaskHandlerImpl(
            namespace,
            localActivityOptions.getDataConverter(),
            laActivityExecutionContextFactory,
            localActivityOptions.getWorkerInterceptors(),
            localActivityOptions.getContextPropagators());
    laWorker = new LocalActivityWorker(namespace, taskQueue, localActivityOptions, laTaskHandler);

    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            namespace,
            factory,
            cache,
            singleWorkerOptions,
            stickyTaskQueueName,
            singleWorkerOptions.getStickyQueueScheduleToStartTimeout(),
            service,
            laWorker.getLocalActivityScheduler());

    workflowWorker =
        new WorkflowWorker(
            service,
            namespace,
            taskQueue,
            stickyTaskQueueName,
            singleWorkerOptions,
            runLocks,
            cache,
            taskHandler,
            eagerActivityDispatcher);

    // Exists to support Worker#replayWorkflowExecution functionality.
    // This handler has to be non-sticky to avoid evicting actual executions from the cache
    WorkflowTaskHandler nonStickyReplayTaskHandler =
        new ReplayWorkflowTaskHandler(
            namespace,
            factory,
            null,
            singleWorkerOptions,
            null,
            Duration.ZERO,
            service,
            laWorker.getLocalActivityScheduler());

    queryReplayHelper = new QueryReplayHelper(nonStickyReplayTaskHandler);
  }

  public void registerWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>[] workflowImplementationTypes) {
    factory.registerWorkflowImplementationTypes(options, workflowImplementationTypes);
  }

  public <R> void registerWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> clazz, Func<R> factory) {
    this.factory.addWorkflowImplementationFactory(options, clazz, factory);
  }

  public void registerLocalActivityImplementations(Object... activitiesImplementation) {
    this.laTaskHandler.registerActivityImplementations(activitiesImplementation);
  }

  @Override
  public void start() {
    workflowWorker.start();
    // It doesn't start if no types are registered with it.
    if (workflowWorker.isStarted()) {
      laWorker.start();
    }
  }

  @Override
  public boolean isStarted() {
    return workflowWorker.isStarted() && (laWorker.isStarted() || !laWorker.isAnyTypeSupported());
  }

  @Override
  public boolean isShutdown() {
    return workflowWorker.isShutdown() || laWorker.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return workflowWorker.isTerminated() && laWorker.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return workflowWorker
        .shutdown(shutdownManager, interruptTasks)
        .thenCompose(ignore -> laWorker.shutdown(shutdownManager, interruptTasks))
        .exceptionally(
            e -> {
              log.error("[BUG] Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = ShutdownManager.awaitTermination(laWorker, unit.toMillis(timeout));
    ShutdownManager.awaitTermination(workflowWorker, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    workflowWorker.suspendPolling();
    laWorker.suspendPolling();
  }

  @Override
  public void resumePolling() {
    workflowWorker.resumePolling();
    laWorker.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return workflowWorker.isSuspended() && laWorker.isSuspended();
  }

  public <R> R queryWorkflowExecution(
      WorkflowExecutionHistory history,
      String queryType,
      Class<R> resultClass,
      Type resultType,
      Object[] args)
      throws Exception {
    Optional<Payloads> serializedArgs = dataConverter.toPayloads(args);
    Optional<Payloads> result =
        queryReplayHelper.queryWorkflowExecution(history, queryType, serializedArgs);
    return dataConverter.fromPayloads(0, result, resultClass, resultType);
  }

  @Override
  public String toString() {
    return String.format(
        "SyncWorkflowWorker{namespace=%s, taskQueue=%s, identity=%s}",
        namespace, taskQueue, identity);
  }
}
