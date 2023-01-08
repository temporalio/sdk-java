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

package io.temporal.testing;

import com.google.common.base.Preconditions;
import com.google.common.collect.ObjectArrays;
import com.google.protobuf.Empty;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest;
import io.temporal.api.testservice.v1.SleepRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.*;
import io.temporal.testserver.TestServer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TestWorkflowEnvironmentInternal implements TestWorkflowEnvironment {

  private final WorkflowClientOptions workflowClientOptions;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final OperatorServiceStubs operatorServiceStubs;
  private final @Nullable TestServiceStubs testServiceStubs;
  private final @Nullable TestServer.InProcessTestServer inProcessServer;
  private final @Nullable TestWorkflowService service;
  private final WorkerFactory workerFactory;
  private final @Nullable TimeLockingInterceptor timeLockingInterceptor;
  private final IdempotentTimeLocker constructorTimeLock;

  public TestWorkflowEnvironmentInternal(@Nullable TestEnvironmentOptions testEnvironmentOptions) {
    if (testEnvironmentOptions == null) {
      testEnvironmentOptions = TestEnvironmentOptions.getDefaultInstance();
    }
    this.workflowClientOptions =
        WorkflowClientOptions.newBuilder(testEnvironmentOptions.getWorkflowClientOptions())
            .validateAndBuildWithDefaults();

    WorkflowServiceStubsOptions.Builder stubsOptionsBuilder =
        testEnvironmentOptions.getWorkflowServiceStubsOptions() != null
            ? WorkflowServiceStubsOptions.newBuilder(
                testEnvironmentOptions.getWorkflowServiceStubsOptions())
            : WorkflowServiceStubsOptions.newBuilder();

    Scope metricsScope = testEnvironmentOptions.getMetricsScope();
    if (metricsScope != null && !(NoopScope.class.equals(metricsScope.getClass()))) {
      stubsOptionsBuilder = stubsOptionsBuilder.setMetricsScope(metricsScope);
    }

    if (testEnvironmentOptions.isUseExternalService()) {
      this.inProcessServer = null;
      this.service = null;
      this.workflowServiceStubs =
          WorkflowServiceStubs.newServiceStubs(
              stubsOptionsBuilder.setTarget(testEnvironmentOptions.getTarget()).build());
      this.testServiceStubs = null;
      this.timeLockingInterceptor = null;
      this.constructorTimeLock = null;
    } else {
      this.inProcessServer =
          TestServer.createServer(true, testEnvironmentOptions.getInitialTimeMillis());
      this.service = fetchWorkflowService();

      WorkflowServiceStubsOptions workflowServiceStubsOptions =
          stubsOptionsBuilder
              .setChannel(this.inProcessServer.getChannel())
              .setTarget(null)
              .validateAndBuildWithDefaults();
      this.workflowServiceStubs = WorkflowServiceStubs.newServiceStubs(workflowServiceStubsOptions);
      this.testServiceStubs =
          TestServiceStubs.newServiceStubs(
              TestServiceStubsOptions.newBuilder(workflowServiceStubsOptions)
                  // we don't want long calls to test service to throw with DEADLINE_EXCEEDED
                  .setRpcTimeout(Duration.ofMillis(Long.MAX_VALUE))
                  .validateAndBuildWithDefaults());
      this.timeLockingInterceptor = new TimeLockingInterceptor(this.testServiceStubs);

      if (!testEnvironmentOptions.isUseTimeskipping()) {
        // If the options ask for no timeskipping, lock one extra time. There will never be a
        // corresponding unlock, so timeskipping will always be off.
        this.constructorTimeLock = new IdempotentTimeLocker(this.testServiceStubs);
        this.constructorTimeLock.lockTimeSkipping();
      } else {
        this.constructorTimeLock = null;
      }
    }

    this.operatorServiceStubs =
        OperatorServiceStubs.newServiceStubs(
            OperatorServiceStubsOptions.newBuilder()
                .setChannel(workflowServiceStubs.getRawChannel())
                .validateAndBuildWithDefaults());

    WorkflowClient client =
        WorkflowClient.newInstance(this.workflowServiceStubs, this.workflowClientOptions);
    this.workerFactory =
        WorkerFactory.newInstance(client, testEnvironmentOptions.getWorkerFactoryOptions());

    testEnvironmentOptions.getSearchAttributes().forEach(this::registerSearchAttribute);
  }

  @SuppressWarnings("deprecation")
  private TestWorkflowService fetchWorkflowService() {
    return this.inProcessServer.getWorkflowService();
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
    return WorkflowClient.newInstance(workflowServiceStubs, options);
  }

  @Override
  public long currentTimeMillis() {
    if (testServiceStubs != null) {
      return ProtobufTimeUtils.toJavaInstant(
              testServiceStubs.blockingStub().getCurrentTime(Empty.newBuilder().build()).getTime())
          .toEpochMilli();
    } else {
      return System.currentTimeMillis();
    }
  }

  @Override
  public void sleep(Duration duration) {
    if (testServiceStubs != null) {
      testServiceStubs
          .blockingStub()
          .unlockTimeSkippingWithSleep(
              SleepRequest.newBuilder()
                  .setDuration(ProtobufTimeUtils.toProtoDuration(duration))
                  .build());
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
  public boolean registerSearchAttribute(String name, IndexedValueType type) {
    if (IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED.equals(type)) {
      throw new IllegalArgumentException(
          "Class " + type + " can't be used as a search attribute type");
    }
    AddSearchAttributesRequest request =
        AddSearchAttributesRequest.newBuilder().putSearchAttributes(name, type).build();
    try {
      operatorServiceStubs.blockingStub().addSearchAttributes(request);
      return true;
    } catch (StatusRuntimeException e) {
      if (Status.Code.ALREADY_EXISTS.equals(e.getStatus().getCode())) {
        return false;
      }
      throw e;
    }
  }

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
  public WorkflowExecutionHistory getWorkflowExecutionHistory(
      @Nonnull WorkflowExecution execution) {
    Preconditions.checkNotNull(execution, "execution is required");
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
    if (testServiceStubs != null) {
      testServiceStubs.shutdownNow();
    }
    operatorServiceStubs.shutdownNow();
    workerFactory.shutdownNow();
    workerFactory.awaitTermination(10, TimeUnit.SECONDS);
    if (constructorTimeLock != null) {
      constructorTimeLock.unlockTimeSkipping();
    }
    workflowServiceStubs.shutdownNow();
    if (testServiceStubs != null) {
      testServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    }
    operatorServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
    workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
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
}
