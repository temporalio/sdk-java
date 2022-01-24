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

import com.uber.m3.tally.Scope;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.worker.MetricsType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.slf4j.MDC;

final class PollTaskExecutor<T> implements ShutdownableTaskExecutor<T> {

  public interface TaskHandler<TT> {
    void handle(TT task) throws Exception;

    Throwable wrapFailure(TT task, Throwable failure);
  }

  private final String namespace;
  private final String taskQueue;
  private final String identity;
  private final TaskHandler<T> handler;
  private final PollerOptions pollerOptions;
  private final Scope metricsScope;

  private final ThreadPoolExecutor taskExecutor;
  private final AtomicInteger availableTaskSlots;
  private final String pollThreadNamePrefix;

  PollTaskExecutor(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nonnull TaskHandler<T> handler,
      @Nonnull PollerOptions pollerOptions,
      int workerTaskSlots,
      @Nonnull Scope metricsScope) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.identity = Objects.requireNonNull(identity);
    this.handler = Objects.requireNonNull(handler);
    this.pollerOptions = Objects.requireNonNull(pollerOptions);
    this.metricsScope = Objects.requireNonNull(metricsScope);

    this.taskExecutor =
        new ThreadPoolExecutor(0, workerTaskSlots, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    this.availableTaskSlots = new AtomicInteger(workerTaskSlots);
    publishSlotsMetric();

    this.pollThreadNamePrefix =
        pollerOptions.getPollThreadNamePrefix().replaceFirst("Poller", "Executor");

    this.taskExecutor.setThreadFactory(
        new ExecutorThreadFactory(
            pollerOptions.getPollThreadNamePrefix().replaceFirst("Poller", "Executor"),
            pollerOptions.getUncaughtExceptionHandler()));
    this.taskExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
  }

  @Override
  public void process(T task) {
    taskExecutor.execute(
        () -> {
          availableTaskSlots.decrementAndGet();
          publishSlotsMetric();
          try {
            MDC.put(LoggerTag.NAMESPACE, namespace);
            MDC.put(LoggerTag.TASK_QUEUE, taskQueue);
            handler.handle(task);
          } catch (Throwable ee) {
            if (!isShutdown()) {
              pollerOptions
                  .getUncaughtExceptionHandler()
                  .uncaughtException(Thread.currentThread(), handler.wrapFailure(task, ee));
            }
          } finally {
            availableTaskSlots.incrementAndGet();
            publishSlotsMetric();
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

  private void publishSlotsMetric() {
    this.metricsScope
        .gauge(MetricsType.WORKER_TASK_SLOTS_AVAILABLE)
        .update(availableTaskSlots.get());
  }
}
