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

package com.uber.cadence.internal.worker;

import com.google.common.base.Preconditions;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.logging.LoggerTag;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

final class PollTaskExecutor<T> implements ShutdownableTaskExecutor<T> {

  public interface TaskHandler<TT> {
    void handle(TT task) throws Exception;

    Throwable wrapFailure(TT task, Throwable failure);
  }

  private final ThreadPoolExecutor taskExecutor;
  private final SingleWorkerOptions options;
  private final String domain;
  private final String taskList;
  private final TaskHandler<T> handler;

  PollTaskExecutor(
      String domain, String taskList, SingleWorkerOptions options, TaskHandler<T> handler) {
    this.domain = domain;
    this.taskList = taskList;
    this.handler = handler;
    Preconditions.checkNotNull(options, "options should not be null");

    this.options = options;
    taskExecutor =
        new ThreadPoolExecutor(
            0,
            options.getTaskExecutorThreadPoolSize(),
            1,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());
    taskExecutor.setThreadFactory(
        new ExecutorThreadFactory(
            options.getPollerOptions().getPollThreadNamePrefix().replaceFirst("Poller", "Executor"),
            options.getPollerOptions().getUncaughtExceptionHandler()));
    taskExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
  }

  @Override
  public void process(T task) {
    taskExecutor.execute(
        () -> {
          MDC.put(LoggerTag.DOMAIN, domain);
          MDC.put(LoggerTag.TASK_LIST, taskList);
          try {
            handler.handle(task);
          } catch (Throwable ee) {
            options
                .getPollerOptions()
                .getUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), handler.wrapFailure(task, ee));
          } finally {
            MDC.remove(LoggerTag.DOMAIN);
            MDC.remove(LoggerTag.TASK_LIST);
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
  public void shutdown() {
    taskExecutor.shutdown();
  }

  @Override
  public void shutdownNow() {
    taskExecutor.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    InternalUtils.awaitTermination(taskExecutor, unit.toMillis(timeout));
  }
}
