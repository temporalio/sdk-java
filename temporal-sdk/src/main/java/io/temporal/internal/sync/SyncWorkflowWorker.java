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

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.internal.activity.ActivityExecutionContextFactory;
import io.temporal.internal.activity.ActivityExecutionContextFactoryImpl;
import io.temporal.internal.activity.LocalActivityExecutionContextFactoryImpl;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.internal.replay.ReplayWorkflowTaskHandler;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.internal.worker.*;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
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
public class SyncWorkflowWorker
    implements SuspendableWorker, Functions.Proc1<PollWorkflowTaskQueueResponse> {
  private static final Logger log = LoggerFactory.getLogger(SyncWorkflowWorker.class);

  private final String identity;
  private final String namespace;
  private final String taskQueue;

  private final WorkflowWorker workflowWorker;
  private final QueryReplayHelper queryReplayHelper;
  private final LocalActivityWorker laWorker;
  private final POJOWorkflowImplementationFactory factory;
  private final DataConverter dataConverter;
  private final POJOActivityTaskHandler laTaskHandler;
  private final ScheduledExecutorService heartbeatExecutor;

  public SyncWorkflowWorker(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      WorkerInterceptor[] workerInterceptors,
      SingleWorkerOptions singleWorkerOptions,
      SingleWorkerOptions localActivityOptions,
      WorkflowExecutorCache cache,
      String stickyTaskQueueName,
      Duration stickyWorkflowTaskScheduleToStartTimeout,
      ThreadPoolExecutor workflowThreadPool) {
    Objects.requireNonNull(workflowThreadPool);

    this.identity = singleWorkerOptions.getIdentity();
    this.namespace = namespace;
    this.taskQueue = taskQueue;

    this.dataConverter = singleWorkerOptions.getDataConverter();

    factory =
        new POJOWorkflowImplementationFactory(
            singleWorkerOptions, workflowThreadPool, workerInterceptors, cache);

    // we should shut down this executor and also give the threads meaningful name
    this.heartbeatExecutor =
        Executors.newScheduledThreadPool(
            4,
            new ExecutorThreadFactory(
                WorkerThreadsNameHelper.getLocalActivityHeartbeatThreadPrefix(namespace, taskQueue),
                // TODO we currently don't have an uncaught exception handler to pass here on
                // options,
                // the closest thing is options.getPollerOptions().getUncaughtExceptionHandler(),
                // but it's pollerOptions, not heartbeat.
                null));
    ActivityExecutionContextFactory activityExecutionContextFactory =
        new ActivityExecutionContextFactoryImpl(
            service,
            localActivityOptions.getIdentity(),
            namespace,
            localActivityOptions.getMaxHeartbeatThrottleInterval(),
            localActivityOptions.getDefaultHeartbeatThrottleInterval(),
            localActivityOptions.getDataConverter(),
            heartbeatExecutor);
    ActivityExecutionContextFactory laActivityExecutionContextFactory =
        new LocalActivityExecutionContextFactoryImpl();
    laTaskHandler =
        new POJOActivityTaskHandler(
            namespace,
            localActivityOptions.getDataConverter(),
            workerInterceptors,
            activityExecutionContextFactory,
            laActivityExecutionContextFactory);
    laWorker = new LocalActivityWorker(namespace, taskQueue, localActivityOptions, laTaskHandler);

    WorkflowTaskHandler taskHandler =
        new ReplayWorkflowTaskHandler(
            namespace,
            factory,
            cache,
            singleWorkerOptions,
            stickyTaskQueueName,
            stickyWorkflowTaskScheduleToStartTimeout,
            service,
            laWorker.getLocalActivityTaskPoller());

    workflowWorker =
        new WorkflowWorker(
            service, namespace, taskQueue, singleWorkerOptions, taskHandler, stickyTaskQueueName);

    // Exists to support Worker#replayWorkflowExecution functionality.
    // This handler has to be non-sticky to avoid evicting actual executions from the cache
    WorkflowTaskHandler nonStickyReplayTaskHandler =
        new ReplayWorkflowTaskHandler(
            namespace,
            factory,
            null,
            singleWorkerOptions,
            null,
            stickyWorkflowTaskScheduleToStartTimeout,
            service,
            laWorker.getLocalActivityTaskPoller());

    queryReplayHelper = new QueryReplayHelper(nonStickyReplayTaskHandler);
  }

  public void registerWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>[] workflowImplementationTypes) {
    factory.registerWorkflowImplementationTypes(options, workflowImplementationTypes);
  }

  public <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> clazz, Func<R> factory) {
    this.factory.addWorkflowImplementationFactory(options, clazz, factory);
  }

  public <R> void addWorkflowImplementationFactory(Class<R> clazz, Func<R> factory) {
    this.factory.addWorkflowImplementationFactory(clazz, factory);
  }

  public void registerLocalActivityImplementations(Object... activitiesImplementation) {
    this.laTaskHandler.registerLocalActivityImplementations(activitiesImplementation);
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
    return workflowWorker.isShutdown() || laWorker.isShutdown() || heartbeatExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return workflowWorker.isTerminated()
        && laWorker.isTerminated()
        && heartbeatExecutor.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return workflowWorker
        .shutdown(shutdownManager, interruptTasks)
        // we want to shutdown heartbeatExecutor before activity worker, so in-flight activities
        // could get an ActivityWorkerShutdownException from their heartbeat
        .thenCompose(
            ignore ->
                shutdownManager.shutdownExecutor(
                    heartbeatExecutor, this + "#heartbeatExecutor", Duration.ofSeconds(5)))
        .thenCompose(ignore -> laWorker.shutdown(shutdownManager, interruptTasks))
        .exceptionally(
            e -> {
              log.error("[BUG] Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = InternalUtils.awaitTermination(laWorker, unit.toMillis(timeout));
    InternalUtils.awaitTermination(workflowWorker, timeoutMillis);
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
  public void apply(PollWorkflowTaskQueueResponse pollWorkflowTaskQueueResponse) {
    workflowWorker.apply(pollWorkflowTaskQueueResponse);
  }

  @Override
  public String toString() {
    return String.format(
        "SyncWorkflowWorker{namespace=%s, taskQueue=%s, identity=%s}",
        namespace, taskQueue, identity);
  }
}
