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

import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class StickyPoller implements SuspendableWorker {
  private final Poller<PollWorkflowTaskQueueResponse> poller;
  private final PollWorkflowTaskDispatcher dispatcher;

  public StickyPoller(
      WorkflowServiceStubs workflowServiceStubs,
      String stickyTaskQueueName,
      int pollThreadCount,
      WorkflowClientOptions workflowClientOptions,
      Scope metricsScope) {
    String namespace = workflowClientOptions.getNamespace();
    Scope stickyScope =
        metricsScope.tagged(
            new ImmutableMap.Builder<String, String>(1)
                .put(MetricsTag.TASK_QUEUE, "sticky")
                .build());
    dispatcher = new PollWorkflowTaskDispatcher(workflowServiceStubs, namespace, metricsScope);
    poller =
        new Poller<>(
            workflowClientOptions.getIdentity(),
            new WorkflowPollTask(
                workflowServiceStubs,
                namespace,
                stickyTaskQueueName,
                workflowClientOptions.getIdentity(),
                workflowClientOptions.getBinaryChecksum(),
                stickyScope),
            dispatcher,
            PollerOptions.newBuilder()
                .setPollThreadNamePrefix(
                    WorkerThreadsNameHelper.getStickyQueueWorkflowPollerThreadPrefix(
                        namespace, stickyTaskQueueName))
                .setPollThreadCount(pollThreadCount)
                .build(),
            stickyScope);
  }

  public void subscribe(String taskQueue, Functions.Proc1<PollWorkflowTaskQueueResponse> consumer) {
    dispatcher.subscribe(taskQueue, consumer);
  }

  public void suspendPolling() {
    poller.suspendPolling();
  }

  public void resumePolling() {
    poller.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return poller.isSuspended();
  }

  @Override
  public void start() {
    poller.start();
  }

  @Override
  public boolean isStarted() {
    return poller.isStarted();
  }

  @Override
  public boolean isShutdown() {
    return poller.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return poller.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    return poller.shutdown(shutdownManager, interruptTasks);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    poller.awaitTermination(timeout, unit);
  }
}
