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

import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.common.WorkflowExecutionHistory;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.internal.replay.ReplayDecisionTaskHandler;
import com.uber.cadence.internal.worker.DecisionTaskHandler;
import com.uber.cadence.internal.worker.LocalActivityWorker;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.internal.worker.SuspendableWorker;
import com.uber.cadence.internal.worker.WorkflowWorker;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.worker.WorkflowImplementationOptions;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.WorkflowInterceptor;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Workflow worker that supports POJO workflow implementations. */
public class SyncWorkflowWorker
    implements SuspendableWorker, Consumer<PollForDecisionTaskResponse> {

  private final WorkflowWorker workflowWorker;
  private final LocalActivityWorker laWorker;
  private final POJOWorkflowImplementationFactory factory;
  private final DataConverter dataConverter;
  private final POJOActivityTaskHandler laTaskHandler;
  private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4);

  public SyncWorkflowWorker(
      IWorkflowService service,
      String domain,
      String taskList,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      SingleWorkerOptions workflowOptions,
      SingleWorkerOptions localActivityOptions,
      DeciderCache cache,
      String stickyTaskListName,
      Duration stickyDecisionScheduleToStartTimeout,
      ThreadPoolExecutor workflowThreadPool) {
    Objects.requireNonNull(workflowThreadPool);
    this.dataConverter = workflowOptions.getDataConverter();

    factory =
        new POJOWorkflowImplementationFactory(
            workflowOptions.getDataConverter(), workflowThreadPool, interceptorFactory, cache);

    laTaskHandler =
        new POJOActivityTaskHandler(
            service, domain, localActivityOptions.getDataConverter(), heartbeatExecutor);
    laWorker = new LocalActivityWorker(domain, taskList, localActivityOptions, laTaskHandler);

    DecisionTaskHandler taskHandler =
        new ReplayDecisionTaskHandler(
            domain,
            factory,
            cache,
            workflowOptions,
            stickyTaskListName,
            stickyDecisionScheduleToStartTimeout,
            service,
            laWorker.getLocalActivityTaskPoller());

    workflowWorker =
        new WorkflowWorker(
            service, domain, taskList, workflowOptions, taskHandler, stickyTaskListName);
  }

  public void setWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>[] workflowImplementationTypes) {
    factory.setWorkflowImplementationTypes(options, workflowImplementationTypes);
  }

  public <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> clazz, Func<R> factory) {
    this.factory.addWorkflowImplementationFactory(options, clazz, factory);
  }

  public <R> void addWorkflowImplementationFactory(Class<R> clazz, Func<R> factory) {
    this.factory.addWorkflowImplementationFactory(clazz, factory);
  }

  public void setLocalActivitiesImplementation(Object... activitiesImplementation) {
    this.laTaskHandler.setLocalActivitiesImplementation(activitiesImplementation);
  }

  @Override
  public void start() {
    workflowWorker.start();
    laWorker.start();
  }

  @Override
  public boolean isStarted() {
    return workflowWorker.isStarted() && laWorker.isStarted();
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
      WorkflowExecution execution,
      String queryType,
      Class<R> resultClass,
      Type resultType,
      Object[] args)
      throws Exception {
    byte[] serializedArgs = dataConverter.toData(args);
    byte[] result = workflowWorker.queryWorkflowExecution(execution, queryType, serializedArgs);
    return dataConverter.fromData(result, resultClass, resultType);
  }

  public <R> R queryWorkflowExecution(
      WorkflowExecutionHistory history,
      String queryType,
      Class<R> resultClass,
      Type resultType,
      Object[] args)
      throws Exception {
    byte[] serializedArgs = dataConverter.toData(args);
    byte[] result = workflowWorker.queryWorkflowExecution(history, queryType, serializedArgs);
    return dataConverter.fromData(result, resultClass, resultType);
  }

  @Override
  public void accept(PollForDecisionTaskResponse pollForDecisionTaskResponse) {
    workflowWorker.accept(pollForDecisionTaskResponse);
  }
}
