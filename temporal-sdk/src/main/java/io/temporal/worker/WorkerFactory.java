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

package io.temporal.worker;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.sync.POJOWorkflowImplementationFactory;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.internal.worker.WorkflowExecutorCache;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Maintains worker creation and lifecycle. */
public final class WorkerFactory extends BaseWorkerFactory {

  private final WorkerFactoryOptions factoryOptions;
  private final ThreadPoolExecutor workflowThreadPool;
  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final AtomicInteger workflowThreadCounter = new AtomicInteger();

  public static WorkerFactory newInstance(WorkflowClient workflowClient) {
    return WorkerFactory.newInstance(workflowClient, WorkerFactoryOptions.getDefaultInstance());
  }

  public static WorkerFactory newInstance(
      WorkflowClient workflowClient, WorkerFactoryOptions options) {
    return new WorkerFactory(workflowClient, options);
  }

  /**
   * Creates a factory. Workers will connect to the temporal server using the workflowService client
   * passed in.
   *
   * @param workflowClient client to the Temporal Service endpoint.
   * @param factoryOptions Options used to configure factory settings
   */
  private WorkerFactory(WorkflowClient workflowClient, WorkerFactoryOptions factoryOptions) {
    super(workflowClient, factoryOptions);
    this.factoryOptions =
        WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();

    this.workflowThreadPool =
        new ThreadPoolExecutor(
            0,
            this.factoryOptions.getMaxWorkflowThreadCount(),
            1,
            TimeUnit.MINUTES,
            new SynchronousQueue<>());
    this.workflowThreadPool.setThreadFactory(
        r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));
    this.workflowThreadExecutor =
        new ActiveThreadReportingExecutor(this.workflowThreadPool, this.metricsScope);
  }

  @Override
  protected ReplayWorkflowFactory newReplayWorkflowFactory(
      WorkerOptions workerOptions,
      WorkflowClientOptions clientOptions,
      WorkflowExecutorCache cache) {
    return new POJOWorkflowImplementationFactory(
        factoryOptions, clientOptions, workerOptions, workflowThreadExecutor, cache);
  }

  @Override
  protected void handleShutdown() {
    workflowThreadPool.shutdownNow();
  }
}
