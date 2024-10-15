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

import io.temporal.client.WorkflowClient;
import io.temporal.internal.nexus.NexusTaskHandlerImpl;
import io.temporal.worker.tuning.NexusSlotInfo;
import io.temporal.worker.tuning.SlotSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncNexusWorker implements SuspendableWorker {
  private static final Logger log = LoggerFactory.getLogger(SyncNexusWorker.class);

  private final String identity;
  private final String namespace;
  private final String taskQueue;
  private final NexusTaskHandlerImpl taskHandler;
  private final NexusWorker worker;

  public SyncNexusWorker(
      WorkflowClient client,
      String namespace,
      String taskQueue,
      SingleWorkerOptions options,
      SlotSupplier<NexusSlotInfo> slotSupplier) {
    this.identity = options.getIdentity();
    this.namespace = namespace;
    this.taskQueue = taskQueue;

    this.taskHandler =
        new NexusTaskHandlerImpl(client, namespace, taskQueue, options.getDataConverter());
    this.worker =
        new NexusWorker(
            client.getWorkflowServiceStubs(),
            namespace,
            taskQueue,
            options,
            taskHandler,
            slotSupplier);
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return worker
        .shutdown(shutdownManager, interruptTasks)
        .thenCompose(r -> taskHandler.shutdown(shutdownManager, interruptTasks))
        .exceptionally(
            e -> {
              log.error("[BUG] Unexpected exception during shutdown", e);
              return null;
            });
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    ShutdownManager.awaitTermination(worker, timeoutMillis);
  }

  @Override
  public boolean start() {
    return worker.start();
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
    return worker.isTerminated();
  }

  @Override
  public WorkerLifecycleState getLifecycleState() {
    return worker.getLifecycleState();
  }

  @Override
  public String toString() {
    return String.format(
        "SyncNexusWorker{namespace=%s, taskQueue=%s, identity=%s}", namespace, taskQueue, identity);
  }

  public void registerNexusServiceImplementation(Object... nexusServiceImplementations) {
    taskHandler.registerNexusServiceImplementations(nexusServiceImplementations);
  }
}
