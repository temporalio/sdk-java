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
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.internal.worker.ActivityTaskHandler.Result;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.workflowservice.CountWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.CountWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.DeprecateDomainRequest;
import io.temporal.proto.workflowservice.DescribeDomainRequest;
import io.temporal.proto.workflowservice.DescribeDomainResponse;
import io.temporal.proto.workflowservice.DescribeTaskListRequest;
import io.temporal.proto.workflowservice.DescribeTaskListResponse;
import io.temporal.proto.workflowservice.DescribeWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.DescribeWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.GetSearchAttributesResponse;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryResponse;
import io.temporal.proto.workflowservice.GetWorkflowExecutionRawHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionRawHistoryResponse;
import io.temporal.proto.workflowservice.ListArchivedWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListArchivedWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.ListClosedWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListClosedWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.ListDomainsRequest;
import io.temporal.proto.workflowservice.ListDomainsResponse;
import io.temporal.proto.workflowservice.ListOpenWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListOpenWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.ListTaskListPartitionsRequest;
import io.temporal.proto.workflowservice.ListTaskListPartitionsResponse;
import io.temporal.proto.workflowservice.ListWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.PollForActivityTaskRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.QueryWorkflowRequest;
import io.temporal.proto.workflowservice.QueryWorkflowResponse;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatByIDRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatResponse;
import io.temporal.proto.workflowservice.RegisterDomainRequest;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.ResetStickyTaskListRequest;
import io.temporal.proto.workflowservice.ResetStickyTaskListResponse;
import io.temporal.proto.workflowservice.ResetWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.ResetWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedResponse;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondQueryTaskCompletedRequest;
import io.temporal.proto.workflowservice.SignalWithStartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.TerminateWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.UpdateDomainRequest;
import io.temporal.proto.workflowservice.UpdateDomainResponse;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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

  public TestActivityEnvironmentInternal(TestEnvironmentOptions options) {
    if (options == null) {
      this.testEnvironmentOptions = new TestEnvironmentOptions.Builder().build();
    } else {
      this.testEnvironmentOptions = options;
    }
    activityTaskHandler =
        new POJOActivityTaskHandler(
            new WorkflowServiceWrapper(workflowService),
            testEnvironmentOptions.getDomain(),
            testEnvironmentOptions.getDataConverter(),
            heartbeatExecutor);
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
  public void setWorkflowService(GrpcWorkflowServiceFactory workflowService) {
    GrpcWorkflowServiceFactory service = new WorkflowServiceWrapper(workflowService);
    this.workflowService = service;
    this.activityTaskHandler.setWorkflowService(service);
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
              .setInput(ByteString.copyFrom(testEnvironmentOptions.getDataConverter().toData(args)))
              .setTaskToken(ByteString.copyFrom("test-task-token", StandardCharsets.UTF_8))
              .setActivityId(String.valueOf(idSequencer.incrementAndGet()))
              .setWorkflowExecution(
                  WorkflowExecution.newBuilder()
                      .setWorkflowId("test-workflow-id")
                      .setRunId(UUID.randomUUID().toString()))
              .setWorkflowType(WorkflowType.newBuilder().setName("test-workflow"))
              .setActivityType(ActivityType.newBuilder().setName(activityType))
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
            response.getTaskFailedResult().getTaskFailedRequest();
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
                taskCancelled.getDetails().toString(StandardCharsets.UTF_8));
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

  private class WorkflowServiceWrapper implements GrpcWorkflowServiceFactory {

    private final GrpcWorkflowServiceFactory impl;

    private WorkflowServiceWrapper(GrpcWorkflowServiceFactory impl) {
      if (impl == null) {
        // Create empty implementation that just ignores all requests.
        this.impl =
            (GrpcWorkflowServiceFactory)
                Proxy.newProxyInstance(
                    WorkflowServiceWrapper.class.getClassLoader(),
                    new Class<?>[] {GrpcWorkflowServiceFactory.class},
                    (proxy, method, args) -> {
                      // noop
                      return method.getReturnType().getDeclaredConstructor().newInstance();
                    });
      } else {
        this.impl = impl;
      }
    }

    @Override
    public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeat(
        RecordActivityTaskHeartbeatRequest heartbeatRequest) {
      if (activityHeartbetListener != null) {
        Object details =
            testEnvironmentOptions
                .getDataConverter()
                .fromData(
                    heartbeatRequest.getDetails().toByteArray(),
                    activityHeartbetListener.valueClass,
                    activityHeartbetListener.valueType);
        activityHeartbetListener.consumer.accept(details);
      }
      // TODO: Cancellation
      return impl.RecordActivityTaskHeartbeat(heartbeatRequest);
    }

    @Override
    public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeatByID(
        RecordActivityTaskHeartbeatByIDRequest heartbeatRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, DomainNotActiveError,
            LimitExceededError, ServiceBusyError, TException {
      return impl.RecordActivityTaskHeartbeatByID(heartbeatRequest);
    }

    @Override
    public void RespondActivityTaskCompleted(RespondActivityTaskCompletedRequest completeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondActivityTaskCompleted(completeRequest);
    }

    @Override
    public void RespondActivityTaskCompletedByID(
        RespondActivityTaskCompletedByIDRequest completeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondActivityTaskCompletedByID(completeRequest);
    }

    @Override
    public void RespondActivityTaskFailed(RespondActivityTaskFailedRequest failRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondActivityTaskFailed(failRequest);
    }

    @Override
    public void RespondActivityTaskFailedByID(RespondActivityTaskFailedByIDRequest failRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondActivityTaskFailedByID(failRequest);
    }

    @Override
    public void RespondActivityTaskCanceled(RespondActivityTaskCanceledRequest canceledRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondActivityTaskCanceled(canceledRequest);
    }

    @Override
    public void RespondActivityTaskCanceledByID(
        RespondActivityTaskCanceledByIDRequest canceledRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondActivityTaskCanceledByID(canceledRequest);
    }

    @Override
    public void RequestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError,
            CancellationAlreadyRequestedError, ServiceBusyError, TException {
      impl.RequestCancelWorkflowExecution(cancelRequest);
    }

    @Override
    public void SignalWorkflowExecution(SignalWorkflowExecutionRequest signalRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            TException {
      impl.SignalWorkflowExecution(signalRequest);
    }

    @Override
    public StartWorkflowExecutionResponse SignalWithStartWorkflowExecution(
        SignalWithStartWorkflowExecutionRequest signalWithStartRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            DomainNotActiveError, LimitExceededError, WorkflowExecutionAlreadyStartedError,
            TException {
      return impl.SignalWithStartWorkflowExecution(signalWithStartRequest);
    }

    @Override
    public ResetWorkflowExecutionResponse ResetWorkflowExecution(
        ResetWorkflowExecutionRequest resetRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            DomainNotActiveError, LimitExceededError, ClientVersionNotSupportedError, TException {
      return impl.ResetWorkflowExecution(resetRequest);
    }

    @Override
    public void TerminateWorkflowExecution(TerminateWorkflowExecutionRequest terminateRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            TException {
      impl.TerminateWorkflowExecution(terminateRequest);
    }

    @Override
    public ListOpenWorkflowExecutionsResponse ListOpenWorkflowExecutions(
        ListOpenWorkflowExecutionsRequest listRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            TException {
      return impl.ListOpenWorkflowExecutions(listRequest);
    }

    @Override
    public ListClosedWorkflowExecutionsResponse ListClosedWorkflowExecutions(
        ListClosedWorkflowExecutionsRequest listRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            TException {
      return impl.ListClosedWorkflowExecutions(listRequest);
    }

    @Override
    public ListWorkflowExecutionsResponse ListWorkflowExecutions(
        ListWorkflowExecutionsRequest listRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            ClientVersionNotSupportedError, TException {
      return impl.ListWorkflowExecutions(listRequest);
    }

    @Override
    public ListArchivedWorkflowExecutionsResponse ListArchivedWorkflowExecutions(
        ListArchivedWorkflowExecutionsRequest listRequest)
        throws BadRequestError, EntityNotExistsError, ServiceBusyError,
            ClientVersionNotSupportedError, TException {
      return impl.ListArchivedWorkflowExecutions(listRequest);
    }

    @Override
    public ListWorkflowExecutionsResponse ScanWorkflowExecutions(
        ListWorkflowExecutionsRequest listRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            ClientVersionNotSupportedError, TException {
      return impl.ScanWorkflowExecutions(listRequest);
    }

    @Override
    public CountWorkflowExecutionsResponse CountWorkflowExecutions(
        CountWorkflowExecutionsRequest countRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            ClientVersionNotSupportedError, TException {
      return impl.CountWorkflowExecutions(countRequest);
    }

    @Override
    public GetSearchAttributesResponse GetSearchAttributes()
        throws InternalServiceError, ServiceBusyError, ClientVersionNotSupportedError, TException {
      return impl.GetSearchAttributes();
    }

    @Override
    public void RespondQueryTaskCompleted(RespondQueryTaskCompletedRequest completeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondQueryTaskCompleted(completeRequest);
    }

    @Override
    public ResetStickyTaskListResponse ResetStickyTaskList(ResetStickyTaskListRequest resetRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, LimitExceededError,
            ServiceBusyError, DomainNotActiveError, TException {
      return impl.ResetStickyTaskList(resetRequest);
    }

    @Override
    public QueryWorkflowResponse QueryWorkflow(QueryWorkflowRequest queryRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, QueryFailedError,
            TException {
      return impl.QueryWorkflow(queryRequest);
    }

    @Override
    public GetWorkflowExecutionRawHistoryResponse GetWorkflowExecutionRawHistory(
        GetWorkflowExecutionRawHistoryRequest getRequest)
        throws BadRequestError, EntityNotExistsError, ServiceBusyError,
            ClientVersionNotSupportedError, TException {
      return impl.GetWorkflowExecutionRawHistory(getRequest);
    }

    @Override
    public DescribeWorkflowExecutionResponse DescribeWorkflowExecution(
        DescribeWorkflowExecutionRequest describeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      return impl.DescribeWorkflowExecution(describeRequest);
    }

    @Override
    public DescribeTaskListResponse DescribeTaskList(DescribeTaskListRequest request)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      return impl.DescribeTaskList(request);
    }

    @Override
    public ClusterInfo GetClusterInfo() throws InternalServiceError, ServiceBusyError, TException {
      return impl.GetClusterInfo();
    }

    @Override
    public ListTaskListPartitionsResponse ListTaskListPartitions(
        ListTaskListPartitionsRequest request)
        throws BadRequestError, EntityNotExistsError, LimitExceededError, ServiceBusyError,
            TException {
      return impl.ListTaskListPartitions(request);
    }

    @Override
    public PollForWorkflowExecutionRawHistoryResponse PollForWorkflowExecutionRawHistory(
        PollForWorkflowExecutionRawHistoryRequest getRequest)
        throws BadRequestError, EntityNotExistsError, ServiceBusyError,
            ClientVersionNotSupportedError, CurrentBranchChangedError, TException {
      return impl.PollForWorkflowExecutionRawHistory(getRequest);
    }

    @Override
    public void RegisterDomain(
        RegisterDomainRequest registerRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RegisterDomain(registerRequest, resultHandler);
    }

    @Override
    public void DescribeDomain(
        DescribeDomainRequest describeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.DescribeDomain(describeRequest, resultHandler);
    }

    @Override
    public void ListDomains(ListDomainsRequest listRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ListDomains(listRequest, resultHandler);
    }

    @Override
    public void UpdateDomain(UpdateDomainRequest updateRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.UpdateDomain(updateRequest, resultHandler);
    }

    @Override
    public void DeprecateDomain(
        DeprecateDomainRequest deprecateRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.DeprecateDomain(deprecateRequest, resultHandler);
    }

    @Override
    public void StartWorkflowExecution(
        StartWorkflowExecutionRequest startRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.StartWorkflowExecution(startRequest, resultHandler);
    }

    @Override
    public void GetWorkflowExecutionHistory(
        GetWorkflowExecutionHistoryRequest getRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.GetWorkflowExecutionHistory(getRequest, resultHandler);
    }

    @Override
    public void PollForDecisionTask(
        PollForDecisionTaskRequest pollRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.PollForDecisionTask(pollRequest, resultHandler);
    }

    @Override
    public void RespondDecisionTaskCompleted(
        RespondDecisionTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondDecisionTaskCompleted(completeRequest, resultHandler);
    }

    @Override
    public void RespondDecisionTaskFailed(
        RespondDecisionTaskFailedRequest failedRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondDecisionTaskFailed(failedRequest, resultHandler);
    }

    @Override
    public void PollForActivityTask(
        PollForActivityTaskRequest pollRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.PollForActivityTask(pollRequest, resultHandler);
    }

    @Override
    public void RecordActivityTaskHeartbeat(
        RecordActivityTaskHeartbeatRequest heartbeatRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RecordActivityTaskHeartbeat(heartbeatRequest, resultHandler);
    }

    @Override
    public void RecordActivityTaskHeartbeatByID(
        RecordActivityTaskHeartbeatByIDRequest heartbeatRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RecordActivityTaskHeartbeatByID(heartbeatRequest, resultHandler);
    }

    @Override
    public void RespondActivityTaskCompleted(
        RespondActivityTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondActivityTaskCompleted(completeRequest, resultHandler);
    }

    @Override
    public void RespondActivityTaskCompletedByID(
        RespondActivityTaskCompletedByIDRequest completeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondActivityTaskCompletedByID(completeRequest, resultHandler);
    }

    @Override
    public void RespondActivityTaskFailed(
        RespondActivityTaskFailedRequest failRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondActivityTaskFailed(failRequest, resultHandler);
    }

    @Override
    public void RespondActivityTaskFailedByID(
        RespondActivityTaskFailedByIDRequest failRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondActivityTaskFailedByID(failRequest, resultHandler);
    }

    @Override
    public void RespondActivityTaskCanceled(
        RespondActivityTaskCanceledRequest canceledRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondActivityTaskCanceled(canceledRequest, resultHandler);
    }

    @Override
    public void RespondActivityTaskCanceledByID(
        RespondActivityTaskCanceledByIDRequest canceledRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondActivityTaskCanceledByID(canceledRequest, resultHandler);
    }

    @Override
    public void RequestCancelWorkflowExecution(
        RequestCancelWorkflowExecutionRequest cancelRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RequestCancelWorkflowExecution(cancelRequest, resultHandler);
    }

    @Override
    public void SignalWorkflowExecution(
        SignalWorkflowExecutionRequest signalRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.SignalWorkflowExecution(signalRequest, resultHandler);
    }

    @Override
    public void SignalWithStartWorkflowExecution(
        SignalWithStartWorkflowExecutionRequest signalWithStartRequest,
        AsyncMethodCallback resultHandler)
        throws TException {
      impl.SignalWithStartWorkflowExecution(signalWithStartRequest, resultHandler);
    }

    @Override
    public void ResetWorkflowExecution(
        ResetWorkflowExecutionRequest resetRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ResetWorkflowExecution(resetRequest, resultHandler);
    }

    @Override
    public void TerminateWorkflowExecution(
        TerminateWorkflowExecutionRequest terminateRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.TerminateWorkflowExecution(terminateRequest, resultHandler);
    }

    @Override
    public void ListOpenWorkflowExecutions(
        ListOpenWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ListOpenWorkflowExecutions(listRequest, resultHandler);
    }

    @Override
    public void ListClosedWorkflowExecutions(
        ListClosedWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ListClosedWorkflowExecutions(listRequest, resultHandler);
    }

    @Override
    public void ListWorkflowExecutions(
        ListWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ListWorkflowExecutions(listRequest, resultHandler);
    }

    @Override
    public void ListArchivedWorkflowExecutions(
        ListArchivedWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ListArchivedWorkflowExecutions(listRequest, resultHandler);
    }

    @Override
    public void ScanWorkflowExecutions(
        ListWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ScanWorkflowExecutions(listRequest, resultHandler);
    }

    @Override
    public void CountWorkflowExecutions(
        CountWorkflowExecutionsRequest countRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.CountWorkflowExecutions(countRequest, resultHandler);
    }

    @Override
    public void GetSearchAttributes(AsyncMethodCallback resultHandler) throws TException {
      impl.GetSearchAttributes(resultHandler);
    }

    @Override
    public void RespondQueryTaskCompleted(
        RespondQueryTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondQueryTaskCompleted(completeRequest, resultHandler);
    }

    @Override
    public void ResetStickyTaskList(
        ResetStickyTaskListRequest resetRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ResetStickyTaskList(resetRequest, resultHandler);
    }

    @Override
    public void QueryWorkflow(QueryWorkflowRequest queryRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.QueryWorkflow(queryRequest, resultHandler);
    }

    @Override
    public void GetWorkflowExecutionRawHistory(
        GetWorkflowExecutionRawHistoryRequest getRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.GetWorkflowExecutionRawHistory(getRequest, resultHandler);
    }

    @Override
    public void DescribeWorkflowExecution(
        DescribeWorkflowExecutionRequest describeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.DescribeWorkflowExecution(describeRequest, resultHandler);
    }

    @Override
    public void DescribeTaskList(DescribeTaskListRequest request, AsyncMethodCallback resultHandler)
        throws TException {
      impl.DescribeTaskList(request, resultHandler);
    }

    @Override
    public void GetClusterInfo(AsyncMethodCallback resultHandler) throws TException {
      impl.GetClusterInfo(resultHandler);
    }

    @Override
    public void ListTaskListPartitions(
        ListTaskListPartitionsRequest request, AsyncMethodCallback resultHandler)
        throws TException {
      impl.ListTaskListPartitions(request, resultHandler);
    }

    @Override
    public void PollForWorkflowExecutionRawHistory(
        PollForWorkflowExecutionRawHistoryRequest getRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.PollForWorkflowExecutionRawHistory(getRequest, resultHandler);
    }

    @Override
    public void RegisterDomain(RegisterDomainRequest registerRequest)
        throws BadRequestError, InternalServiceError, DomainAlreadyExistsError, TException {
      impl.RegisterDomain(registerRequest);
    }

    @Override
    public DescribeDomainResponse DescribeDomain(DescribeDomainRequest describeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      return impl.DescribeDomain(describeRequest);
    }

    @Override
    public ListDomainsResponse ListDomains(ListDomainsRequest listRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            TException {
      return impl.ListDomains(listRequest);
    }

    @Override
    public UpdateDomainResponse UpdateDomain(UpdateDomainRequest updateRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      return impl.UpdateDomain(updateRequest);
    }

    @Override
    public void DeprecateDomain(DeprecateDomainRequest deprecateRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.DeprecateDomain(deprecateRequest);
    }

    @Override
    public StartWorkflowExecutionResponse StartWorkflowExecution(
        StartWorkflowExecutionRequest startRequest)
        throws BadRequestError, InternalServiceError, WorkflowExecutionAlreadyStartedError,
            ServiceBusyError, TException {
      return impl.StartWorkflowExecution(startRequest);
    }

    @Override
    public GetWorkflowExecutionHistoryResponse GetWorkflowExecutionHistory(
        GetWorkflowExecutionHistoryRequest getRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
            TException {
      return impl.GetWorkflowExecutionHistory(getRequest);
    }

    @Override
    public PollForDecisionTaskResponse PollForDecisionTask(PollForDecisionTaskRequest pollRequest)
        throws BadRequestError, InternalServiceError, ServiceBusyError, TException {
      return impl.PollForDecisionTask(pollRequest);
    }

    @Override
    public RespondDecisionTaskCompletedResponse RespondDecisionTaskCompleted(
        RespondDecisionTaskCompletedRequest completeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      return impl.RespondDecisionTaskCompleted(completeRequest);
    }

    @Override
    public void RespondDecisionTaskFailed(RespondDecisionTaskFailedRequest failedRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondDecisionTaskFailed(failedRequest);
    }

    @Override
    public PollForActivityTaskResponse PollForActivityTask(PollForActivityTaskRequest pollRequest)
        throws BadRequestError, InternalServiceError, ServiceBusyError, TException {
      return impl.PollForActivityTask(pollRequest);
    }

    @Override
    public void close() {
      impl.close();
    }
  }
}
