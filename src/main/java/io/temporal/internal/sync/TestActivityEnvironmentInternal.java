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

import com.google.common.base.Defaults;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.workflow.ActivityFailureException;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterceptor;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TestActivityEnvironmentInternal implements TestActivityEnvironment {

  private final POJOActivityTaskHandler activityTaskHandler;
  private final TestEnvironmentOptions testEnvironmentOptions;
  private final AtomicInteger idSequencer = new AtomicInteger();
  private ClassConsumerPair<Object> activityHeartbetListener;
  private static final ScheduledExecutorService heartbeatExecutor =
      Executors.newScheduledThreadPool(20);
  private GrpcWorkflowServiceFactory workflowService;
  private Server mockServer;
  private AtomicBoolean cancellationRequested = new AtomicBoolean();

  public TestActivityEnvironmentInternal(TestEnvironmentOptions options) {
    if (options == null) {
      this.testEnvironmentOptions = new TestEnvironmentOptions.Builder().build();
    } else {
      this.testEnvironmentOptions = options;
    }

    // Initialize an in-memory mock service.
    String serverName = InProcessServerBuilder.generateName();
    try {
      mockServer =
          InProcessServerBuilder.forName(serverName)
              .directExecutor()
              .addService(new HeartbeatInterceptingService())
              .build()
              .start();
    } catch (IOException e) {
      // This should not happen with in-memory services, but rethrow just in case.
      throw new RuntimeException(e);
    }
    // TODO: (vkoby) Ideally the channel needs to be closed after use, find a way to do that.
    workflowService =
        new GrpcWorkflowServiceFactory(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());
    activityTaskHandler =
        new POJOActivityTaskHandler(
            workflowService,
            testEnvironmentOptions.getDomain(),
            testEnvironmentOptions.getDataConverter(),
            heartbeatExecutor);
  }

  private class HeartbeatInterceptingService extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    @Override
    public void recordActivityTaskHeartbeat(
        RecordActivityTaskHeartbeatRequest request,
        StreamObserver<RecordActivityTaskHeartbeatResponse> responseObserver) {
      try {
        if (activityHeartbetListener != null) {
          Object details =
              testEnvironmentOptions
                  .getDataConverter()
                  .fromData(
                      request.getDetails().toByteArray(),
                      activityHeartbetListener.valueClass,
                      activityHeartbetListener.valueType);
          activityHeartbetListener.consumer.accept(details);
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

  /**
   * Register activity implementation objects with a worker. Overwrites previously registered
   * objects. As activities are reentrant and stateless only one instance per activity type is
   * registered.
   *
   * <p>Implementations that share a worker must implement different interfaces as an activity type
   * is identified by the activity interface, not by the implementation.
   *
   * <p>
   */
  @Override
  public void registerActivitiesImplementations(Object... activityImplementations) {
    activityTaskHandler.setActivitiesImplementation(activityImplementations);
  }

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   */
  @Override
  public <T> T newActivityStub(Class<T> activityInterface) {
    ActivityOptions options =
        new ActivityOptions.Builder().setScheduleToCloseTimeout(Duration.ofDays(1)).build();
    InvocationHandler invocationHandler =
        ActivityInvocationHandler.newInstance(options, new TestActivityExecutor(workflowService));
    invocationHandler = new DeterministicRunnerWrapper(invocationHandler);
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  @Override
  public void requestCancelActivity() {
    cancellationRequested.set(true);
  }

  @Override
  public <T> void setActivityHeartbeatListener(Class<T> detailsClass, Consumer<T> listener) {
    setActivityHeartbeatListener(detailsClass, detailsClass, listener);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> void setActivityHeartbeatListener(
      Class<T> detailsClass, Type detailsType, Consumer<T> listener) {
    activityHeartbetListener = new ClassConsumerPair(detailsClass, detailsType, listener);
  }

  @Override
  public void close() {
    mockServer.shutdown();
    try {
      mockServer.awaitTermination();
    } catch (InterruptedException e) {
      throw Activity.wrap(e);
    }
  }

  private class TestActivityExecutor implements WorkflowInterceptor {

    @SuppressWarnings("UnusedVariable")
    private final GrpcWorkflowServiceFactory workflowService;

    TestActivityExecutor(GrpcWorkflowServiceFactory workflowService) {
      this.workflowService = workflowService;
    }

    @Override
    public <T> Promise<T> executeActivity(
        String activityType,
        Class<T> resultClass,
        Type resultType,
        Object[] args,
        ActivityOptions options) {
      PollForActivityTaskResponse task =
          PollForActivityTaskResponse.newBuilder()
              .setScheduleToCloseTimeoutSeconds(
                  (int) options.getScheduleToCloseTimeout().getSeconds())
              .setHeartbeatTimeoutSeconds((int) options.getHeartbeatTimeout().getSeconds())
              .setStartToCloseTimeoutSeconds((int) options.getStartToCloseTimeout().getSeconds())
              .setScheduledTimestamp(Duration.ofMillis(System.currentTimeMillis()).toNanos())
              .setStartedTimestamp(Duration.ofMillis(System.currentTimeMillis()).toNanos())
              .setInput(
                  args != null
                      ? ByteString.copyFrom(testEnvironmentOptions.getDataConverter().toData(args))
                      : ByteString.EMPTY)
              .setTaskToken(ByteString.copyFrom("test-task-token".getBytes(StandardCharsets.UTF_8)))
              .setActivityId(String.valueOf(idSequencer.incrementAndGet()))
              .setWorkflowExecution(
                  WorkflowExecution.newBuilder()
                      .setWorkflowId("test-workflow-id")
                      .setRunId(UUID.randomUUID().toString())
                      .build())
              .setActivityType(ActivityType.newBuilder().setName(activityType).build())
              .build();
      Result taskResult = activityTaskHandler.handle(task, NoopScope.getInstance(), false);
      return Workflow.newPromise(getReply(task, taskResult, resultClass, resultType));
    }

    @Override
    public <R> Promise<R> executeLocalActivity(
        String activityName,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        LocalActivityOptions options) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public <R> WorkflowResult<R> executeChildWorkflow(
        String workflowType,
        Class<R> resultClass,
        Type resultType,
        Object[] args,
        ChildWorkflowOptions options) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Random newRandom() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Promise<Void> signalExternalWorkflow(
        WorkflowExecution execution, String signalName, Object[] args) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Promise<Void> cancelWorkflow(WorkflowExecution execution) {
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
    public int getVersion(String changeID, int minSupported, int maxSupported) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void continueAsNew(
        Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object[] args) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void registerQuery(String queryType, Type[] argTypes, Func1<Object[], Object> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public UUID randomUUID() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void upsertSearchAttributes(Map<String, Object> searchAttributes) {
      throw new UnsupportedOperationException("not implemented");
    }

    private <T> T getReply(
        PollForActivityTaskResponse task,
        ActivityTaskHandler.Result response,
        Class<T> resultClass,
        Type resultType) {
      RespondActivityTaskCompletedRequest taskCompleted = response.getTaskCompleted();
      if (taskCompleted != null) {
        return testEnvironmentOptions
            .getDataConverter()
            .fromData(taskCompleted.getResult().toByteArray(), resultClass, resultType);
      } else {
        RespondActivityTaskFailedRequest taskFailed =
            response.getTaskFailed().getTaskFailedRequest();
        if (taskFailed != null) {
          String causeClassName = taskFailed.getReason();
          Class<? extends Exception> causeClass;
          Exception cause;
          try {
            @SuppressWarnings("unchecked") // cc is just to have a place to put this annotation
            Class<? extends Exception> cc =
                (Class<? extends Exception>) Class.forName(causeClassName);
            causeClass = cc;
            cause =
                testEnvironmentOptions
                    .getDataConverter()
                    .fromData(taskFailed.getDetails().toByteArray(), causeClass, causeClass);
          } catch (Exception e) {
            cause = e;
          }
          throw new ActivityFailureException(
              0, task.getActivityType(), task.getActivityId(), cause);

        } else {
          RespondActivityTaskCanceledRequest taskCancelled = response.getTaskCancelled();
          if (taskCancelled != null) {
            throw new CancellationException(
                new String(taskCancelled.getDetails().toByteArray(), StandardCharsets.UTF_8));
          }
        }
      }
      return Defaults.defaultValue(resultClass);
    }
  }

  private static class ClassConsumerPair<T> {

    final Consumer<T> consumer;
    final Class<T> valueClass;
    final Type valueType;

    ClassConsumerPair(Class<T> valueClass, Type valueType, Consumer<T> consumer) {
      this.valueClass = Objects.requireNonNull(valueClass);
      this.valueType = Objects.requireNonNull(valueType);
      this.consumer = Objects.requireNonNull(consumer);
    }
  }
}
