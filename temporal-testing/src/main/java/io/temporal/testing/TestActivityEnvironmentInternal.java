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

import com.google.common.base.Defaults;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.activity.ActivityExecutionContextFactory;
import io.temporal.internal.activity.ActivityExecutionContextFactoryImpl;
import io.temporal.internal.activity.ActivityTaskHandlerImpl;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.sync.*;
import io.temporal.internal.testservice.InProcessGRPCServer;
import io.temporal.internal.worker.ActivityTask;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TestActivityEnvironmentInternal implements TestActivityEnvironment {
  private static final Logger log = LoggerFactory.getLogger(TestActivityEnvironmentInternal.class);

  private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(20);
  private final ExecutorService activityWorkerExecutor =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "test-service-activity-worker"));
  private final ExecutorService deterministicRunnerExecutor =
      new ThreadPoolExecutor(
          1,
          1000,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> new Thread(r, "test-service-deterministic-runner"));
  private final AtomicBoolean cancellationRequested = new AtomicBoolean();
  private final AtomicInteger idSequencer = new AtomicInteger();
  private final InProcessGRPCServer mockServer;
  private final ActivityTaskHandlerImpl activityTaskHandler;
  private final TestEnvironmentOptions testEnvironmentOptions;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final AtomicReference<Object> heartbeatDetails = new AtomicReference<>();
  private ClassConsumerPair<Object> activityHeartbeatListener;

  public TestActivityEnvironmentInternal(@Nullable TestEnvironmentOptions options) {
    // Initialize an in-memory mock service.
    this.mockServer =
        new InProcessGRPCServer(Collections.singletonList(new HeartbeatInterceptingService()));
    this.testEnvironmentOptions =
        options != null
            ? TestEnvironmentOptions.newBuilder(options).validateAndBuildWithDefaults()
            : TestEnvironmentOptions.newBuilder().validateAndBuildWithDefaults();
    WorkflowServiceStubsOptions.Builder serviceStubsOptionsBuilder =
        WorkflowServiceStubsOptions.newBuilder(
                testEnvironmentOptions.getWorkflowServiceStubsOptions())
            .setTarget(null)
            .setChannel(this.mockServer.getChannel())
            .setRpcQueryTimeout(Duration.ofSeconds(60));
    Scope metricsScope = this.testEnvironmentOptions.getMetricsScope();
    if (metricsScope != null && !(NoopScope.class.equals(metricsScope.getClass()))) {
      serviceStubsOptionsBuilder.setMetricsScope(metricsScope);
    }
    this.workflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(serviceStubsOptionsBuilder.build());

    ActivityExecutionContextFactory activityExecutionContextFactory =
        new ActivityExecutionContextFactoryImpl(
            workflowServiceStubs,
            testEnvironmentOptions.getWorkflowClientOptions().getIdentity(),
            testEnvironmentOptions.getWorkflowClientOptions().getNamespace(),
            WorkerOptions.getDefaultInstance().getMaxHeartbeatThrottleInterval(),
            WorkerOptions.getDefaultInstance().getDefaultHeartbeatThrottleInterval(),
            testEnvironmentOptions.getWorkflowClientOptions().getDataConverter(),
            heartbeatExecutor);
    activityTaskHandler =
        new ActivityTaskHandlerImpl(
            testEnvironmentOptions.getWorkflowClientOptions().getNamespace(),
            "test-activity-env-task-queue",
            testEnvironmentOptions.getWorkflowClientOptions().getDataConverter(),
            activityExecutionContextFactory,
            testEnvironmentOptions.getWorkerFactoryOptions().getWorkerInterceptors(),
            testEnvironmentOptions.getWorkflowClientOptions().getContextPropagators());
  }

  private class HeartbeatInterceptingService extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    @Override
    public void recordActivityTaskHeartbeat(
        RecordActivityTaskHeartbeatRequest request,
        StreamObserver<RecordActivityTaskHeartbeatResponse> responseObserver) {
      try {
        if (activityHeartbeatListener != null) {
          Optional<Payloads> requestDetails =
              request.hasDetails() ? Optional.of(request.getDetails()) : Optional.empty();

          Object details =
              testEnvironmentOptions
                  .getWorkflowClientOptions()
                  .getDataConverter()
                  .fromPayloads(
                      0,
                      requestDetails,
                      activityHeartbeatListener.valueClass,
                      activityHeartbeatListener.valueType);
          activityHeartbeatListener.consumer.apply(details);
        }
        responseObserver.onNext(
            RecordActivityTaskHeartbeatResponse.newBuilder()
                .setCancelRequested(cancellationRequested.get())
                .build());
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        responseObserver.onError(e);
      }
    }
  }

  @Override
  public void registerActivitiesImplementations(Object... activityImplementations) {
    activityTaskHandler.registerActivityImplementations(activityImplementations);
  }

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   */
  @Override
  public <T> T newActivityStub(Class<T> activityInterface) {
    ActivityOptions options =
        ActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofDays(1))
            .setHeartbeatTimeout(Duration.ofSeconds(1))
            .build();
    InvocationHandler invocationHandler =
        ActivityInvocationHandler.newInstance(
            activityInterface, options, null, new TestActivityExecutor(), () -> {});
    invocationHandler =
        new DeterministicRunnerWrapper(invocationHandler, deterministicRunnerExecutor::submit);
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   * @param options options that specify the activity invocation parameters
   */
  @Override
  public <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
    InvocationHandler invocationHandler =
        ActivityInvocationHandler.newInstance(
            activityInterface, options, null, new TestActivityExecutor(), () -> {});
    invocationHandler =
        new DeterministicRunnerWrapper(invocationHandler, deterministicRunnerExecutor::submit);
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   * @param options options that specify the activity invocation parameters
   * @param activityMethodOptions activity method-specific invocation parameters
   */
  @Override
  public <T> T newLocalActivityStub(
      Class<T> activityInterface,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> activityMethodOptions) {
    InvocationHandler invocationHandler =
        LocalActivityInvocationHandler.newInstance(
            activityInterface,
            options,
            activityMethodOptions,
            new TestActivityExecutor(),
            () -> {});
    invocationHandler =
        new DeterministicRunnerWrapper(invocationHandler, deterministicRunnerExecutor::submit);
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  @Override
  public void requestCancelActivity() {
    cancellationRequested.set(true);
  }

  @Override
  public <T> void setActivityHeartbeatListener(Class<T> detailsClass, Functions.Proc1<T> listener) {
    setActivityHeartbeatListener(detailsClass, detailsClass, listener);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> void setActivityHeartbeatListener(
      Class<T> detailsClass, Type detailsType, Functions.Proc1<T> listener) {
    activityHeartbeatListener = new ClassConsumerPair(detailsClass, detailsType, listener);
  }

  @Override
  public <T> void setHeartbeatDetails(T details) {
    heartbeatDetails.set(details);
  }

  @Override
  public void close() {
    heartbeatExecutor.shutdownNow();
    activityWorkerExecutor.shutdownNow();
    deterministicRunnerExecutor.shutdownNow();
    workflowServiceStubs.shutdown();
    mockServer.shutdown();
    mockServer.awaitTermination(5, TimeUnit.SECONDS);
  }

  private class TestActivityExecutor implements WorkflowOutboundCallsInterceptor {

    @Override
    public <T> ActivityOutput<T> executeActivity(ActivityInput<T> i) {
      Optional<Payloads> payloads =
          testEnvironmentOptions
              .getWorkflowClientOptions()
              .getDataConverter()
              .toPayloads(i.getArgs());
      Optional<Payloads> heartbeatPayload =
          Optional.ofNullable(heartbeatDetails.getAndSet(null))
              .flatMap(
                  obj ->
                      testEnvironmentOptions
                          .getWorkflowClientOptions()
                          .getDataConverter()
                          .toPayloads(obj));

      ActivityOptions options = i.getOptions();
      PollActivityTaskQueueResponse.Builder taskBuilder =
          PollActivityTaskQueueResponse.newBuilder()
              .setScheduleToCloseTimeout(
                  ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()))
              .setHeartbeatTimeout(ProtobufTimeUtils.toProtoDuration(options.getHeartbeatTimeout()))
              .setStartToCloseTimeout(
                  ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()))
              .setScheduledTime(ProtobufTimeUtils.getCurrentProtoTime())
              .setStartedTime(ProtobufTimeUtils.getCurrentProtoTime())
              .setTaskToken(ByteString.copyFrom("test-task-token".getBytes(StandardCharsets.UTF_8)))
              .setActivityId(String.valueOf(idSequencer.incrementAndGet()))
              .setWorkflowExecution(
                  WorkflowExecution.newBuilder()
                      .setWorkflowId("test-workflow-id")
                      .setRunId(UUID.randomUUID().toString())
                      .build())
              .setActivityType(ActivityType.newBuilder().setName(i.getActivityName()).build());
      payloads.ifPresent(taskBuilder::setInput);
      heartbeatPayload.ifPresent(taskBuilder::setHeartbeatDetails);
      PollActivityTaskQueueResponse task = taskBuilder.build();
      return new ActivityOutput<>(
          task.getActivityId(),
          Workflow.newPromise(
              getReply(task, executeActivity(task, false), i.getResultClass(), i.getResultType())));
    }

    @Override
    public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> i) {
      Optional<Payloads> payloads =
          testEnvironmentOptions
              .getWorkflowClientOptions()
              .getDataConverter()
              .toPayloads(i.getArgs());
      LocalActivityOptions options = i.getOptions();
      PollActivityTaskQueueResponse.Builder taskBuilder =
          PollActivityTaskQueueResponse.newBuilder()
              .setScheduleToCloseTimeout(
                  ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()))
              .setStartToCloseTimeout(
                  ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()))
              .setScheduledTime(ProtobufTimeUtils.getCurrentProtoTime())
              .setStartedTime(ProtobufTimeUtils.getCurrentProtoTime())
              .setTaskToken(ByteString.copyFrom("test-task-token".getBytes(StandardCharsets.UTF_8)))
              .setActivityId(String.valueOf(idSequencer.incrementAndGet()))
              .setWorkflowExecution(
                  WorkflowExecution.newBuilder()
                      .setWorkflowId("test-workflow-id")
                      .setRunId(UUID.randomUUID().toString())
                      .build())
              .setActivityType(ActivityType.newBuilder().setName(i.getActivityName()).build());
      payloads.ifPresent(taskBuilder::setInput);
      PollActivityTaskQueueResponse task = taskBuilder.build();
      return new LocalActivityOutput<>(
          Workflow.newPromise(
              getReply(task, executeActivity(task, true), i.getResultClass(), i.getResultType())));
    }

    /**
     * We execute the activity task on a separate activity worker to emulate what actually happens
     * in our production setup
     *
     * @param activityTask activity task to execute
     * @param localActivity true if it's a local activity
     * @return result of activity execution
     */
    private Result executeActivity(
        PollActivityTaskQueueResponse activityTask, boolean localActivity) {
      //noinspection DataFlowIssue -- no permit for the LA in this test
      Future<Result> activityFuture =
          activityWorkerExecutor.submit(
              () ->
                  activityTaskHandler.handle(
                      new ActivityTask(activityTask, null, () -> {}),
                      testEnvironmentOptions.getMetricsScope(),
                      localActivity));

      try {
        return activityFuture.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      } catch (ExecutionException e) {
        log.error("Exception during processing of activity task");
        throw new RuntimeException(e);
      }
    }

    @Override
    public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
        ExecuteNexusOperationInput<R> input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Random newRandom() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public CancelWorkflowOutput cancelWorkflow(CancelWorkflowInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void sleep(Duration duration) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void await(String reason, Supplier<Boolean> unblockCondition) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Promise<Void> newTimer(Duration duration) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public <R> R mutableSideEffect(
        String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public int getVersion(String changeId, int minSupported, int maxSupported) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void continueAsNew(ContinueAsNewInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void registerQuery(RegisterQueryInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void registerSignalHandlers(RegisterSignalHandlersInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void registerDynamicSignalHandler(RegisterDynamicSignalHandlerInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void registerDynamicQueryHandler(RegisterDynamicQueryHandlerInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public UUID randomUUID() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void upsertSearchAttributes(Map<String, ?> searchAttributes) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void upsertTypedSearchAttributes(SearchAttributeUpdate<?>... searchAttributeUpdates) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void upsertMemo(Map<String, Object> memo) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Object newChildThread(Runnable runnable, boolean detached, String name) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public long currentTimeMillis() {
      throw new UnsupportedOperationException("not implemented");
    }

    private <T> T getReply(
        PollActivityTaskQueueResponse task,
        ActivityTaskHandler.Result response,
        Class<T> resultClass,
        Type resultType) {
      DataConverter dataConverter =
          testEnvironmentOptions.getWorkflowClientOptions().getDataConverter();
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        Optional<Payloads> result =
            taskCompleted.hasResult() ? Optional.of(taskCompleted.getResult()) : Optional.empty();
        return dataConverter.fromPayloads(0, result, resultClass, resultType);
      } else {
        RespondActivityTaskFailedRequest taskFailed =
            response.getTaskFailed().getTaskFailedRequest();
        if (taskFailed != null) {
          Exception cause = dataConverter.failureToException(taskFailed.getFailure());
          throw new ActivityFailure(
              taskFailed.getFailure().getMessage(),
              0,
              0,
              task.getActivityType().getName(),
              task.getActivityId(),
              RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
              "TestActivityEnvironment",
              cause);
        } else {
          RespondActivityTaskCanceledRequest taskCanceled = response.getTaskCanceled();
          if (taskCanceled != null) {
            throw new CanceledFailure(
                "canceled",
                new EncodedValues(
                    taskCanceled.hasDetails()
                        ? Optional.of(taskCanceled.getDetails())
                        : Optional.empty(),
                    dataConverter),
                null);
          }
        }
      }
      return Defaults.defaultValue(resultClass);
    }

    @Override
    public void registerUpdateHandlers(RegisterUpdateHandlersInput input) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void registerDynamicUpdateHandler(RegisterDynamicUpdateHandlerInput input) {
      throw new UnsupportedOperationException("not implemented");
    }
  }

  private static class ClassConsumerPair<T> {

    final Functions.Proc1<T> consumer;
    final Class<T> valueClass;
    final Type valueType;

    ClassConsumerPair(Class<T> valueClass, Type valueType, Functions.Proc1<T> consumer) {
      this.valueClass = Objects.requireNonNull(valueClass);
      this.valueType = Objects.requireNonNull(valueType);
      this.consumer = Objects.requireNonNull(consumer);
    }
  }
}
