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

package io.temporal.testing;

import com.google.common.base.Preconditions;
import com.google.common.collect.ObjectArrays;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.internal.sync.WorkflowClientInternal;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testserver.TestServer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public final class TestWorkflowEnvironmentInternal implements TestWorkflowEnvironment {

  private final WorkflowClientOptions workflowClientOptions;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final @Nullable TestServer.InProcessTestServer inProcessServer;
  private final @Nullable TestWorkflowService service;
  private final WorkerFactory workerFactory;
  private final @Nullable TimeLockingInterceptor timeLockingInterceptor;

  public TestWorkflowEnvironmentInternal(TestEnvironmentOptions testEnvironmentOptions) {
    if (testEnvironmentOptions == null) {
      testEnvironmentOptions = TestEnvironmentOptions.getDefaultInstance();
    }
    workflowClientOptions =
        WorkflowClientOptions.newBuilder(testEnvironmentOptions.getWorkflowClientOptions())
            .validateAndBuildWithDefaults();

    WorkflowServiceStubsOptions.Builder stubsOptionsBuilder =
        testEnvironmentOptions.getWorkflowServiceStubsOptions() != null
            ? WorkflowServiceStubsOptions.newBuilder(
                testEnvironmentOptions.getWorkflowServiceStubsOptions())
            : WorkflowServiceStubsOptions.newBuilder();

    stubsOptionsBuilder =
        stubsOptionsBuilder.setMetricsScope(testEnvironmentOptions.getMetricsScope());

    if (testEnvironmentOptions.isUseExternalService()) {
      workflowServiceStubs =
          WorkflowServiceStubs.newInstance(
              stubsOptionsBuilder.setTarget(testEnvironmentOptions.getTarget()).build());
      inProcessServer = null;
      service = null;
      timeLockingInterceptor = null;
    } else {
      inProcessServer =
          TestServer.createServer(true, testEnvironmentOptions.getInitialTimeMillis());
      service = inProcessServer.getWorkflowService();
      timeLockingInterceptor = new TimeLockingInterceptor(service);

      if (!testEnvironmentOptions.isUseTimeskipping()) {
        // If the options ask for no timeskipping, lock one extra time. There will never be a
        // corresponding unlock, so timeskipping will always be off.
        service.lockTimeSkipping("TestEnvironmentOptions.isUseTimeskipping was false");
      }

      workflowServiceStubs =
          WorkflowServiceStubs.newInstance(
              stubsOptionsBuilder.setChannel(inProcessServer.getChannel()).setTarget(null).build());
    }
    WorkflowClient client = WorkflowClient.newInstance(workflowServiceStubs, workflowClientOptions);
    workerFactory =
        WorkerFactory.newInstance(client, testEnvironmentOptions.getWorkerFactoryOptions());
  }

  @Override
  public Worker newWorker(String taskQueue) {
    return workerFactory.newWorker(taskQueue, WorkerOptions.getDefaultInstance());
  }

  @Override
  public Worker newWorker(String taskQueue, WorkerOptions options) {
    return workerFactory.newWorker(taskQueue, options);
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    WorkflowClientOptions options;
    if (timeLockingInterceptor != null) {
      options =
          WorkflowClientOptions.newBuilder(workflowClientOptions)
              .setInterceptors(
                  ObjectArrays.concat(
                      workflowClientOptions.getInterceptors(), timeLockingInterceptor))
              .build();
    } else {
      options = workflowClientOptions;
    }
    return WorkflowClientInternal.newInstance(workflowServiceStubs, options);
  }

  @Override
  public long currentTimeMillis() {
    if (service != null) {
      return service.currentTimeMillis();
    } else {
      return System.currentTimeMillis();
    }
  }

  @Override
  public void sleep(Duration duration) {
    if (service != null) {
      service.sleep(duration);
    } else {
      try {
        Thread.sleep(duration.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void registerDelayedCallback(Duration delay, Runnable r) {
    Preconditions.checkState(
        service != null, "registerDelayedCallback is not supported with the external service");
    service.registerDelayedCallback(delay, r);
  }

  @Override
  @Deprecated
  public WorkflowServiceStubs getWorkflowService() {
    return getWorkflowServiceStubs();
  }

  @Override
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return workflowServiceStubs;
  }

  @Override
  public String getNamespace() {
    return workflowClientOptions.getNamespace();
  }

  @Override
  public String getDiagnostics() {
    Preconditions.checkState(
        service != null, "getDiagnostics is not supported with the external service");
    StringBuilder result = new StringBuilder();
    service.getDiagnostics(result);
    return result.toString();
  }

  @Override
  public WorkflowExecutionHistory getWorkflowExecutionHistory(WorkflowExecution execution) {
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(getNamespace())
            .setExecution(execution)
            .build();
    return new WorkflowExecutionHistory(
        workflowServiceStubs.blockingStub().getWorkflowExecutionHistory(request).getHistory());
  }

  @Override
  public void close() {
    workerFactory.shutdownNow();
    workerFactory.awaitTermination(10, TimeUnit.SECONDS);
    workflowServiceStubs.shutdownNow();
    workflowServiceStubs.awaitTermination(10, TimeUnit.SECONDS);
    if (inProcessServer != null) {
      inProcessServer.close();
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
  @Deprecated
  public void shutdownTestService() {
    if (service != null) {
      service.close();
    }
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

  private static class TimeLockingInterceptor extends WorkflowClientInterceptorBase {

    private final IdempotentLocker locker;

    TimeLockingInterceptor(TestWorkflowService service) {
      this.locker = new IdempotentLocker(service);
    }

    @Deprecated
    @Override
    public WorkflowStub newUntypedWorkflowStub(
        String workflowType, WorkflowOptions options, WorkflowStub next) {
      return new TimeLockingWorkflowStub(locker, next);
    }

    @Deprecated
    @Override
    public WorkflowStub newUntypedWorkflowStub(
        WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next) {
      return new TimeLockingWorkflowStub(locker, next);
    }

    @Override
    public ActivityCompletionClient newActivityCompletionClient(ActivityCompletionClient next) {
      return next;
    }

    /**
     * Used to ensure that multiple TimeLockingWorkflowStubs that are blocked at the same time from
     * multiple threads execute unlock only once and the lock only once.
     */
    private static class IdempotentLocker {
      private final TestWorkflowService store;
      private final AtomicInteger count = new AtomicInteger(1);

      private IdempotentLocker(TestWorkflowService store) {
        this.store = store;
      }

      public void lockTimeSkipping(String caller) {
        if (count.incrementAndGet() == 1) {
          store.lockTimeSkipping(caller);
        }
      }

      public void unlockTimeSkipping(String caller) {
        if (count.decrementAndGet() == 0) {
          store.unlockTimeSkipping(caller);
        }
      }
    }

    private static class TimeLockingWorkflowStub implements WorkflowStub {

      private final IdempotentLocker locker;
      private final WorkflowStub next;

      TimeLockingWorkflowStub(IdempotentLocker locker, WorkflowStub next) {
        this.locker = locker;
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
        locker.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(resultClass, resultType);
        } finally {
          locker.lockTimeSkipping("TimeLockingWorkflowStub getResult");
        }
      }

      @Override
      public <R> R getResult(Class<R> resultClass) {
        locker.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(resultClass);
        } finally {
          locker.lockTimeSkipping("TimeLockingWorkflowStub getResult");
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
        locker.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(timeout, unit, resultClass, resultType);
        } finally {
          locker.lockTimeSkipping("TimeLockingWorkflowStub getResult");
        }
      }

      @Override
      public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
          throws TimeoutException {
        locker.unlockTimeSkipping("TimeLockingWorkflowStub getResult");
        try {
          return next.getResult(timeout, unit, resultClass);
        } finally {
          locker.lockTimeSkipping("TimeLockingWorkflowStub getResult");
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
      public void terminate(String reason, Object... details) {
        next.terminate(reason, details);
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
                    locker.lockTimeSkipping(
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
          locker.unlockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          try {
            return super.get();
          } finally {
            locker.lockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          }
        }

        @Override
        public R get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
          locker.unlockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          try {
            return super.get(timeout, unit);
          } finally {
            locker.lockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture get");
          }
        }

        @Override
        public R join() {
          locker.unlockTimeSkipping("TimeLockingWorkflowStub TimeLockingFuture join");
          return super.join();
        }
      }
    }
  }
}
