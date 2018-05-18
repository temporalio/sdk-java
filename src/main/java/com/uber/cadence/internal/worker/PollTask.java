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

import com.uber.cadence.internal.logging.LoggerTag;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

/**
 * Assumes that there is only one instance of PollTask per worker as it contains thread pool and
 * semaphore.
 */
final class PollTask<T> implements Poller.ThrowingRunnable {

  public interface TaskHandler<TT> {
    void handle(IWorkflowService service, String domain, String taskList, TT task) throws Exception;

    TT poll(IWorkflowService service, String domain, String taskList) throws Exception;

    Throwable wrapFailure(TT task, Throwable failure);
  }

  @FunctionalInterface
  public interface Poller<T> {
    T poll() throws Exception;
  }

  private final IWorkflowService service;
  private final String domain;
  private final String taskList;
  private final TaskHandler<T> handler;
  private ThreadPoolExecutor taskExecutor;
  private Semaphore pollSemaphore;
  private final SingleWorkerOptions options;

  PollTask(
      IWorkflowService service,
      String domain,
      String taskList,
      SingleWorkerOptions options,
      TaskHandler<T> handler) {
    this.service = service;
    this.domain = domain;
    this.taskList = taskList;
    this.options = options;
    this.handler = handler;
    taskExecutor =
        new ThreadPoolExecutor(
            options.getTaskExecutorThreadPoolSize(),
            options.getTaskExecutorThreadPoolSize(),
            10,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());
    taskExecutor.setThreadFactory(
        new ExecutorThreadFactory(
            options.getPollerOptions().getPollThreadNamePrefix() + " " + taskList + " ",
            options.getPollerOptions().getUncaughtExceptionHandler()));
    taskExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
    this.pollSemaphore = new Semaphore(options.getTaskExecutorThreadPoolSize());
  }

  /** Poll for a task and execute correspondent implementation using provided executor service. */
  @Override
  public void run() throws Exception {
    boolean synchronousSemaphoreRelease = false;
    try {
      pollSemaphore.acquire();
      // we will release the semaphore in a finally clause
      synchronousSemaphoreRelease = true;
      MDC.put(LoggerTag.DOMAIN, domain);
      MDC.put(LoggerTag.TASK_LIST, taskList);
      final T task = handler.poll(service, domain, taskList);
      if (task == null) {
        return;
      }
      synchronousSemaphoreRelease = false; // released by the task
      try {
        taskExecutor.execute(
            () -> {
              MDC.put(LoggerTag.DOMAIN, domain);
              MDC.put(LoggerTag.TASK_LIST, taskList);
              try {
                handler.handle(service, domain, taskList, task);
              } catch (Throwable ee) {
                options
                    .getPollerOptions()
                    .getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), handler.wrapFailure(task, ee));
              } finally {
                pollSemaphore.release();
                MDC.remove(LoggerTag.DOMAIN);
                MDC.remove(LoggerTag.TASK_LIST);
              }
            });
      } catch (Error | Exception e) {
        synchronousSemaphoreRelease = true;
        throw e;
      }
    } finally {
      if (synchronousSemaphoreRelease) {
        pollSemaphore.release();
      }
      MDC.remove(LoggerTag.DOMAIN);
      MDC.remove(LoggerTag.TASK_LIST);
    }
  }
}
