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

package io.temporal.internal.worker;

import com.google.common.base.Preconditions;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.logging.LoggerTag;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

final class PollTaskExecutor<T> implements ShutdownableTaskExecutor<T> {

  public interface TaskHandler<TT> {
    void handle(TT task) throws Exception;

    Throwable wrapFailure(TT task, Throwable failure);
  }

  private final String namespace;
  private final String taskQueue;
  private final SingleWorkerOptions options;
  private final String identity;

  private final ThreadPoolExecutor taskExecutor;
  private final String pollThreadNamePrefix;
  private final TaskHandler<T> handler;

  PollTaskExecutor(
      String namespace, String taskQueue, SingleWorkerOptions options, TaskHandler<T> handler) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.options = options;
    this.identity = options.getIdentity();

    this.handler = handler;
    Preconditions.checkNotNull(options, "options should not be null");

    this.taskExecutor =
        new ThreadPoolExecutor(
            0,
            options.getTaskExecutorThreadPoolSize(),
            1,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());

    this.pollThreadNamePrefix =
        options.getPollerOptions().getPollThreadNamePrefix().replaceFirst("Poller", "Executor");

    this.taskExecutor.setThreadFactory(
        new ExecutorThreadFactory(
            options.getPollerOptions().getPollThreadNamePrefix().replaceFirst("Poller", "Executor"),
            options.getPollerOptions().getUncaughtExceptionHandler()));
    this.taskExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
  }

  @Override
  public void process(T task) {
    taskExecutor.execute(
        () -> {
          MDC.put(LoggerTag.NAMESPACE, namespace);
          MDC.put(LoggerTag.TASK_QUEUE, taskQueue);
          try {
            handler.handle(task);
          } catch (Throwable ee) {
            if (!isShutdown()) {
              options
                  .getPollerOptions()
                  .getUncaughtExceptionHandler()
                  .uncaughtException(Thread.currentThread(), handler.wrapFailure(task, ee));
            }
          } finally {
            MDC.remove(LoggerTag.NAMESPACE);
            MDC.remove(LoggerTag.TASK_QUEUE);
          }
        });
  }

  @Override
  public boolean isShutdown() {
    return taskExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return taskExecutor.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    String taskExecutorName = this + "#taskExecutor";
    return interruptTasks
        ? shutdownManager.shutdownExecutorNowUntimed(taskExecutor, taskExecutorName)
        : shutdownManager.shutdownExecutorUntimed(taskExecutor, taskExecutorName);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    InternalUtils.awaitTermination(taskExecutor, unit.toMillis(timeout));
  }

  @Override
  public String toString() {
    // TODO using pollThreadNamePrefix here is ugly. We should consider introducing some concept of
    // WorkerContext [workerIdentity, namespace, queue, local/non-local if applicable] and pass it
    // around
    // that will simplify such kind of logging through workers.
    return String.format("PollTaskExecutor{name=%s, identity=%s}", pollThreadNamePrefix, identity);
  }
}
