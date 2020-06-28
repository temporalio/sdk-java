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

package io.temporal.internal.sync;

import com.google.common.collect.ObjectArrays;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TestWorkflowEnvironmentInternal implements TestWorkflowEnvironment {

  private final TestEnvironmentOptions testEnvironmentOptions;
  private final WorkflowClientOptions workflowClientOptions;
  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final TestWorkflowService service;
  private final WorkerFactory workerFactory;

  public TestWorkflowEnvironmentInternal(TestEnvironmentOptions options) {
    if (options == null) {
      this.testEnvironmentOptions = TestEnvironmentOptions.getDefaultInstance();
    } else {
      this.testEnvironmentOptions = options;
    }
    workflowClientOptions =
        WorkflowClientOptions.newBuilder(testEnvironmentOptions.getWorkflowClientOptions())
            .validateAndBuildWithDefaults();
    workerFactoryOptions =
        WorkerFactoryOptions.newBuilder(testEnvironmentOptions.getWorkerFactoryOptions())
            .validateAndBuildWithDefaults();
    service = new TestWorkflowService();
    service.lockTimeSkipping("TestWorkflowEnvironmentInternal constructor");
    workflowServiceStubs =
        WorkflowServiceStubs.newInstance(
            service,
            WorkflowServiceStubsOptions.newBuilder()
                .setMetricsScope(options.getMetricsScope())
                .build());

    WorkflowClient client = WorkflowClient.newInstance(workflowServiceStubs, workflowClientOptions);
    workerFactory = WorkerFactory.newInstance(client, options.getWorkerFactoryOptions());
  }

  @Override
  public Worker newWorker(String taskQueue) {
    Worker result = workerFactory.newWorker(taskQueue, WorkerOptions.getDefaultInstance());
    return result;
  }

  @Override
  public Worker newWorker(String taskQueue, WorkerOptions options) {
    Worker result = workerFactory.newWorker(taskQueue, options);
    return result;
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder(workflowClientOptions)
            .setInterceptors(
                ObjectArrays.concat(
                    workflowClientOptions.getInterceptors(), new TimeLockingInterceptor(service)))
            .build();
    return WorkflowClientInternal.newInstance(workflowServiceStubs, options);
  }

  @Override
  public long currentTimeMillis() {
    return service.currentTimeMillis();
  }

  @Override
  public void sleep(Duration duration) {
    service.sleep(duration);
  }

  @Override
  public void registerDelayedCallback(Duration delay, Runnable r) {
    service.registerDelayedCallback(delay, r);
  }

  @Override
  public WorkflowServiceStubs getWorkflowService() {
    return workflowServiceStubs;
  }

  @Override
  public String getNamespace() {
    return workflowClientOptions.getNamespace();
  }

  @Override
  public String getDiagnostics() {
    StringBuilder result = new StringBuilder();
    service.getDiagnostics(result);
    return result.toString();
  }

  @Override
  public void close() {
    workerFactory.shutdownNow();
    workerFactory.awaitTermination(10, TimeUnit.SECONDS);
    service.close();
    workflowServiceStubs.shutdown();
    try {
      workflowServiceStubs.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
    }
  }

  @Override
  public void start() {
    workerFactory.start();
  }

  @Override
  public boolean isStarted() {
    return workerFactory.isStarted();
  }

  @Override
  public boolean isShutdown() {
    return workerFactory.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return workerFactory.isTerminated();
  }

  @Override
  public void shutdown() {
    workerFactory.shutdown();
  }

  @Override
  public void shutdownNow() {
    workerFactory.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    workerFactory.awaitTermination(timeout, unit);
  }

  @Override
  public WorkerFactory getWorkerFactory() {
    return workerFactory;
  }

  private static class TimeLockingInterceptor implements WorkflowClientInterceptor {

    private final TestWorkflowService service;

    TimeLockingInterceptor(TestWorkflowService service) {
      this.service = service;
    }

    @Override
    public WorkflowStub newUntypedWorkflowStub(
        String workflowType, WorkflowOptions options, WorkflowStub next) {
      return new TimeLockingWorkflowStub(service, next);
    }

    @Override
    public WorkflowStub newUntypedWorkflowStub(
        WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next) {
      return new TimeLockingWorkflowStub(service, next);
    }

    @Override
    public ActivityCompletionClient newActivityCompletionClient(ActivityCompletionClient next) {
      return next;
    }

    private class TimeLockingWorkflowStub implements WorkflowStub {

      private final TestWorkflowService service;
      private final WorkflowStub next;

      TimeLockingWorkflowStub(TestWorkflowService service, WorkflowStub next) {
        this.service = service;
        this.next = next;
      }

      @Override
      public void signal(String signalName, Object... args) {
        next.signal(signalName, args);
      }

      @Override
      public WorkflowExecution start(Object... args) {
        return next.start(args);
      }

      @Override
      public WorkflowExecution signalWithStart(
          String signalName, Object[] signalArgs, Object[] startArgs) {
        return next.signalWithStart(signalName, signalArgs, startArgs);
      }

      @Override
      public Optional<String> getWorkflowType() {
        return next.getWorkflowType();
      }

      @Override
      public WorkflowExecution getExecution() {
        return next.getExecution();
      }

      @Override
      public <R> R getResult(Class<R> resultClass, Type resultType) {
        service.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(resultClass, resultType);
        } finally {
          service.lockTimeSkipping("TimeLockingWorkflowStub getResult");
        }
      }

      @Override
      public <R> R getResult(Class<R> resultClass) {
        service.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(resultClass);
        } finally {
          service.lockTimeSkipping("TimeLockingWorkflowStub getResult");
        }
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, Type resultType) {
        return new TimeLockingFuture<>(next.getResultAsync(resultClass, resultType));
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
        return new TimeLockingFuture<>(next.getResultAsync(resultClass));
      }

      @Override
      public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType)
          throws TimeoutException {
        service.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(timeout, unit, resultClass, resultType);
        } finally {
          service.lockTimeSkipping("TimeLockingWorkflowStub getResult");
        }
      }

      @Override
      public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
          throws TimeoutException {
        service.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(timeout, unit, resultClass);
        } finally {
          service.lockTimeSkipping("TimeLockingWorkflowStub getResult");
        }
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(
          long timeout, TimeUnit unit, Class<R> resultClass, Type resultType) {
        return new TimeLockingFuture<>(next.getResultAsync(timeout, unit, resultClass, resultType));
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(
          long timeout, TimeUnit unit, Class<R> resultClass) {
        return new TimeLockingFuture<>(next.getResultAsync(timeout, unit, resultClass));
      }

      @Override
      public <R> R query(String queryType, Class<R> resultClass, Object... args) {
        return next.query(queryType, resultClass, args);
      }

      @Override
      public <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args) {
        return next.query(queryType, resultClass, resultType, args);
      }

      @Override
      public void cancel() {
        next.cancel();
      }

      @Override
      public Optional<WorkflowOptions> getOptions() {
        return next.getOptions();
      }

      /** Unlocks time skipping before blocking calls and locks back after completion. */
      private class TimeLockingFuture<R> extends CompletableFuture<R> {

        public TimeLockingFuture(CompletableFuture<R> resultAsync) {
          @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
          CompletableFuture<R> ignored =
              resultAsync.whenComplete(
                  (r, e) -> {
                    service.lockTimeSkipping(
                        "TimeLockingWorkflowStub TimeLockingFuture constructor");
                    if (e == null) {
                      this.complete(r);
                    } else {
                      this.completeExceptionally(e);
                    }
                  });
        }

        @Override
        public R get() throws InterruptedException, ExecutionException {
          service.unlockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          try {
            return super.get();
          } finally {
            service.lockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          }
        }

        @Override
        public R get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
          service.unlockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          try {
            return super.get(timeout, unit);
          } finally {
            service.lockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          }
        }

        @Override
        public R join() {
          service.unlockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture join");
          return super.join();
        }
      }
    }
  }
}
