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

import com.google.common.base.Preconditions;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.task.VirtualThreadDelegate;
import java.util.Objects;
import java.util.concurrent.*;
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

  private final ExecutorService taskExecutor;
  private final String pollThreadNamePrefix;

  PollTaskExecutor(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nonnull TaskHandler<T> handler,
      @Nonnull PollerOptions pollerOptions,
      int workerTaskSlots,
      boolean synchronousQueue,
      boolean enableVirtualThreads) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.identity = Objects.requireNonNull(identity);
    this.handler = Objects.requireNonNull(handler);
    this.pollerOptions = Objects.requireNonNull(pollerOptions);

    this.pollThreadNamePrefix =
        pollerOptions.getPollThreadNamePrefix().replaceFirst("Poller", "Executor");
    //
    if (enableVirtualThreads) {
      AtomicInteger threadIndex = new AtomicInteger();
      this.taskExecutor =
          VirtualThreadDelegate.newVirtualThreadExecutor(
              (t) -> {
                t.setName(this.pollThreadNamePrefix + ": " + threadIndex.incrementAndGet());
                t.setUncaughtExceptionHandler(pollerOptions.getUncaughtExceptionHandler());
              });
    } else {
      ThreadPoolExecutor threadPoolTaskExecutor =
          new ThreadPoolExecutor(
              // for SynchronousQueue we can afford to set it to 0, because the queue is always full
              // or empty
              // for LinkedBlockingQueue we have to set slots to workerTaskSlots to avoid situation
              // when the queue grows, but the amount of threads is not, because the queue is not
              // (and
              // never) full
              synchronousQueue ? 0 : workerTaskSlots,
              workerTaskSlots,
              10,
              TimeUnit.SECONDS,
              synchronousQueue ? new SynchronousQueue<>() : new LinkedBlockingQueue<>());
      threadPoolTaskExecutor.allowCoreThreadTimeOut(true);
      threadPoolTaskExecutor.setThreadFactory(
          new ExecutorThreadFactory(
              this.pollThreadNamePrefix, pollerOptions.getUncaughtExceptionHandler()));
      threadPoolTaskExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
      this.taskExecutor = threadPoolTaskExecutor;
    }
  }

  @Override
  public void process(@Nonnull T task) {
    Preconditions.checkNotNull(task, "task");
    taskExecutor.execute(
        () -> {
          try {
            MDC.put(LoggerTag.NAMESPACE, namespace);
            MDC.put(LoggerTag.TASK_QUEUE, taskQueue);
            handler.handle(task);
          } catch (Throwable e) {
            if (!isShutdown()) {
              pollerOptions
                  .getUncaughtExceptionHandler()
                  .uncaughtException(Thread.currentThread(), handler.wrapFailure(task, e));
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
    ShutdownManager.awaitTermination(taskExecutor, unit.toMillis(timeout));
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
