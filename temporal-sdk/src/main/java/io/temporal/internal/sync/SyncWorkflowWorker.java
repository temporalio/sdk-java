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
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.internal.replay.ReplayWorkflowTaskHandler;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.SuspendableWorker;
import io.temporal.internal.worker.WorkflowTaskHandler;
import io.temporal.internal.worker.WorkflowWorker;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Workflow worker that supports POJO workflow implementations. */
public class SyncWorkflowWorker
    implements SuspendableWorker, Functions.Proc1<PollWorkflowTaskQueueResponse> {

  private final WorkflowWorker workflowWorker;
  private final LocalActivityWorker laWorker;
  private final POJOWorkflowImplementationFactory factory;
  private final DataConverter dataConverter;
  private final POJOActivityTaskHandler laTaskHandler;

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
    this.dataConverter = singleWorkerOptions.getDataConverter();

    factory =
        new POJOWorkflowImplementationFactory(
            singleWorkerOptions, workflowThreadPool, workerInterceptors, cache);

    ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4);
    laTaskHandler =
        new POJOActivityTaskHandler(
            service,
            localActivityOptions.getIdentity(),
            namespace,
            localActivityOptions.getDataConverter(),
            heartbeatExecutor,
            workerInterceptors);
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
            this::isShutdown,
            laWorker.getLocalActivityTaskPoller());

    workflowWorker =
        new WorkflowWorker(
            service, namespace, taskQueue, singleWorkerOptions, taskHandler, stickyTaskQueueName);
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
    return workflowWorker.isShutdown() && laWorker.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return workflowWorker.isTerminated() && laWorker.isTerminated();
  }

  @Override
  public void shutdown() {
    laWorker.shutdown();
    workflowWorker.shutdown();
  }

  @Override
  public void shutdownNow() {
    laWorker.shutdownNow();
    workflowWorker.shutdownNow();
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
        workflowWorker.queryWorkflowExecution(history, queryType, serializedArgs);
    return dataConverter.fromPayloads(0, result, resultClass, resultType);
  }

  @Override
  public void apply(PollWorkflowTaskQueueResponse pollWorkflowTaskQueueResponse) {
    workflowWorker.apply(pollWorkflowTaskQueueResponse);
  }
}
