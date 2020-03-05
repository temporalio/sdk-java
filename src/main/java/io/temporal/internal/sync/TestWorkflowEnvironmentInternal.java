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

import io.temporal.*;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientInterceptor;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.common.QueryResponse;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.IWorkflowService;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

public final class TestWorkflowEnvironmentInternal implements TestWorkflowEnvironment {

  private final TestEnvironmentOptions testEnvironmentOptions;
  private final WorkflowServiceWrapper service;
  private final Worker.Factory workerFactory;

  public TestWorkflowEnvironmentInternal(TestEnvironmentOptions options) {
    if (options == null) {
      this.testEnvironmentOptions = new TestEnvironmentOptions.Builder().build();
    } else {
      this.testEnvironmentOptions = options;
    }
    service = new WorkflowServiceWrapper();
    service.lockTimeSkipping("TestWorkflowEnvironmentInternal constructor");
    workerFactory =
        new Worker.Factory(service, options.getDomain(), options.getWorkerFactoryOptions());
  }

  @Override
  public Worker newWorker(String taskList) {
    return newWorker(taskList, x -> x);
  }

  @Override
  public Worker newWorker(
      String taskList, Function<WorkerOptions.Builder, WorkerOptions.Builder> overrideOptions) {
    WorkerOptions.Builder builder =
        new WorkerOptions.Builder()
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
    return WorkflowClientInternal.newInstance(service, testEnvironmentOptions.getDomain(), options);
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
        service, testEnvironmentOptions.getDomain(), newOptions);
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
  public IWorkflowService getWorkflowService() {
    return service;
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

  private static class WorkflowServiceWrapper implements IWorkflowService {

    private final TestWorkflowService impl;

    private WorkflowServiceWrapper() {
      impl = new TestWorkflowService();
    }

    public long currentTimeMillis() {
      return impl.currentTimeMillis();
    }

    @Override
    public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeat(
        RecordActivityTaskHeartbeatRequest heartbeatRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
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
      impl.GetSearchAttributes();
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
    public void GetClusterInfo(AsyncMethodCallback resultHandler) throws TException {}

    @Override
    public void ListTaskListPartitions(
        ListTaskListPartitionsRequest request, AsyncMethodCallback resultHandler)
        throws TException {}

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

    public void getDiagnostics(StringBuilder result) {
      impl.getDiagnostics(result);
    }

    @Override
    public void close() {
      impl.close();
    }

    public void registerDelayedCallback(Duration delay, Runnable r) {
      impl.registerDelayedCallback(delay, r);
    }

    public void lockTimeSkipping(String caller) {
      impl.lockTimeSkipping(caller);
    }

    public void unlockTimeSkipping(String caller) {
      impl.unlockTimeSkipping(caller);
    }

    public void sleep(Duration duration) {
      impl.sleep(duration);
    }
  }

  private static class TimeLockingInterceptor implements WorkflowClientInterceptor {

    private final WorkflowServiceWrapper service;

    TimeLockingInterceptor(WorkflowServiceWrapper service) {
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

      private final WorkflowServiceWrapper service;
      private final WorkflowStub next;

      TimeLockingWorkflowStub(WorkflowServiceWrapper service, WorkflowStub next) {
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
