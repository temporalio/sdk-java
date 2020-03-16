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

package io.temporal.internal.sync;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.temporal.activity.Activity;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientInterceptor;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.common.QueryResponse;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.enums.QueryRejectCondition;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class TestWorkflowEnvironmentInternal implements TestWorkflowEnvironment {

  private final TestEnvironmentOptions testEnvironmentOptions;
  private ManagedChannel channel;
  private Server server;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final TestWorkflowService service;
  private final Worker.Factory workerFactory;

  public TestWorkflowEnvironmentInternal(TestEnvironmentOptions options) {
    if (options == null) {
      this.testEnvironmentOptions = new TestEnvironmentOptions.Builder().build();
    } else {
      this.testEnvironmentOptions = options;
    }
    service = new TestWorkflowService();
    service.lockTimeSkipping("TestWorkflowEnvironmentInternal constructor");
    String serverName = InProcessServerBuilder.generateName();
    // Initialize an in-memory mock service.
    try {
      server =
          InProcessServerBuilder.forName(serverName)
              .directExecutor()
              .addService(ServerInterceptors.intercept(service, new LoggingInterceptor()))
              .build()
              .start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    workflowServiceStubs = WorkflowServiceStubs.newInstance(channel);
    workerFactory =
        new Worker.Factory(
            workflowServiceStubs, options.getDomain(), options.getWorkerFactoryOptions());
  }

  @Override
  public Worker newWorker(String taskList) {
    return newWorker(taskList, x -> x);
  }

  @Override
  public Worker newWorker(
      String taskList, Function<WorkerOptions.Builder, WorkerOptions.Builder> overrideOptions) {
    WorkerOptions.Builder builder =
        WorkerOptions.newBuilder()
            .setInterceptorFactory(testEnvironmentOptions.getInterceptorFactory())
            .setMetricsScope(testEnvironmentOptions.getMetricsScope())
            .setEnableLoggingInReplay(testEnvironmentOptions.isLoggingEnabledInReplay());
    if (testEnvironmentOptions.getDataConverter() != null) {
      builder.setDataConverter(testEnvironmentOptions.getDataConverter());
    }
    builder = overrideOptions.apply(builder);
    Worker result = workerFactory.newWorker(taskList, builder.build());
    return result;
  }

  @Override
  public WorkflowClient newWorkflowClient() {
    WorkflowClientOptions options =
        new WorkflowClientOptions.Builder()
            .setDataConverter(testEnvironmentOptions.getDataConverter())
            .setInterceptors(new TimeLockingInterceptor(service))
            .setMetricsScope(testEnvironmentOptions.getMetricsScope())
            .build();
    return WorkflowClientInternal.newInstance(
        workflowServiceStubs, testEnvironmentOptions.getDomain(), options);
  }

  @Override
  public WorkflowClient newWorkflowClient(WorkflowClientOptions options) {
    WorkflowClientInterceptor[] existingInterceptors = options.getInterceptors();
    WorkflowClientInterceptor[] interceptors =
        new WorkflowClientInterceptor[existingInterceptors.length + 1];
    System.arraycopy(existingInterceptors, 0, interceptors, 0, existingInterceptors.length);
    interceptors[interceptors.length - 1] = new TimeLockingInterceptor(service);
    WorkflowClientOptions newOptions =
        new WorkflowClientOptions.Builder(options).setInterceptors(interceptors).build();
    return WorkflowClientInternal.newInstance(
        workflowServiceStubs, testEnvironmentOptions.getDomain(), newOptions);
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
  public String getDomain() {
    return testEnvironmentOptions.getDomain();
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
    workflowServiceStubs.shutdownNow();
    channel.shutdownNow();
    try {
      workflowServiceStubs.awaitTermination(10, TimeUnit.SECONDS);
      channel.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
    }
    server.shutdown();
    try {
      server.awaitTermination();
    } catch (InterruptedException e) {
      throw Activity.wrap(e);
    }
    service.close();
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
  public Worker.Factory getWorkerFactory() {
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
      public <R> QueryResponse<R> query(
          String queryType,
          Class<R> resultClass,
          QueryRejectCondition queryRejectCondition,
          Object... args) {
        return next.query(queryType, resultClass, queryRejectCondition, args);
      }

      @Override
      public <R> QueryResponse<R> query(
          String queryType,
          Class<R> resultClass,
          Type resultType,
          QueryRejectCondition queryRejectCondition,
          Object... args) {
        return next.query(queryType, resultClass, resultType, queryRejectCondition, args);
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
