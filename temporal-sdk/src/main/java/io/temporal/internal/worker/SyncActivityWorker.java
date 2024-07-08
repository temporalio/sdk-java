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

import io.temporal.internal.activity.ActivityExecutionContextFactory;
import io.temporal.internal.activity.ActivityExecutionContextFactoryImpl;
import io.temporal.internal.activity.ActivityTaskHandlerImpl;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Activity worker that supports POJO activity implementations. */
public class SyncActivityWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(SyncActivityWorker.class);

  private final String identity;
  private final String namespace;
  private final String taskQueue;

  private final ScheduledExecutorService heartbeatExecutor;
  private final ActivityTaskHandlerImpl taskHandler;
  private final ActivityWorker worker;

  public SyncActivityWorker(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      double taskQueueActivitiesPerSecond,
      SingleWorkerOptions options) {
    this.identity = options.getIdentity();
    this.namespace = namespace;
    this.taskQueue = taskQueue;

    this.heartbeatExecutor =
        Executors.newScheduledThreadPool(
            4,
            new ExecutorThreadFactory(
                WorkerThreadsNameHelper.getActivityHeartbeatThreadPrefix(namespace, taskQueue),
                // TODO we currently don't have an uncaught exception handler to pass here on
                // options,
                // the closest thing is options.getPollerOptions().getUncaughtExceptionHandler(),
                // but it's pollerOptions, not heartbeat.
                null));
    ActivityExecutionContextFactory activityExecutionContextFactory =
        new ActivityExecutionContextFactoryImpl(
            service,
            identity,
            namespace,
            options.getMaxHeartbeatThrottleInterval(),
            options.getDefaultHeartbeatThrottleInterval(),
            options.getDataConverter(),
            heartbeatExecutor);
    this.taskHandler =
        new ActivityTaskHandlerImpl(
            namespace,
            taskQueue,
            options.getDataConverter(),
            activityExecutionContextFactory,
            options.getWorkerInterceptors(),
            options.getContextPropagators());
    this.worker =
        new ActivityWorker(
            service, namespace, taskQueue, taskQueueActivitiesPerSecond, options, taskHandler);
  }

  public void registerActivityImplementations(Object... activitiesImplementation) {
    taskHandler.registerActivityImplementations(activitiesImplementation);
  }

  @Override
  public boolean start() {
    return worker.start();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return shutdownManager
        // we want to shut down heartbeatExecutor before activity worker, so in-flight activities
        // could get an ActivityWorkerShutdownException from their heartbeat
        .shutdownExecutor(heartbeatExecutor, this + "#heartbeatExecutor", Duration.ofSeconds(5))
        .thenCompose(r -> worker.shutdown(shutdownManager, interruptTasks))
        .exceptionally(
            e -> {
              log.error("[BUG] Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    timeoutMillis = ShutdownManager.awaitTermination(worker, timeoutMillis);
    ShutdownManager.awaitTermination(heartbeatExecutor, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    worker.suspendPolling();
  }

  @Override
  public void resumePolling() {
    worker.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return worker.isSuspended();
  }

  @Override
  public boolean isShutdown() {
    return worker.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return worker.isTerminated() && heartbeatExecutor.isTerminated();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    WorkerLifecycleState lifecycleState = worker.getLifecycleState();
    if (WorkerLifecycleState.TERMINATED.equals(lifecycleState)) {
      // return TERMINATED only if both worker and heartbeatExecutor are terminated
      return heartbeatExecutor.isTerminated()
          ? WorkerLifecycleState.TERMINATED
          : WorkerLifecycleState.SHUTDOWN;
    } else {
      return lifecycleState;
    }
  }

  public EagerActivityDispatcher getEagerActivityDispatcher() {
    return this.worker.getEagerActivityDispatcher();
  }

  @Override
  public String toString() {
    return String.format(
        "SyncActivityWorker{namespace=%s, taskQueue=%s, identity=%s}",
        namespace, taskQueue, identity);
  }
}
