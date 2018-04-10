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

package com.uber.cadence.internal.sync;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.CancellationAlreadyRequestedError;
import com.uber.cadence.DeprecateDomainRequest;
import com.uber.cadence.DescribeDomainRequest;
import com.uber.cadence.DescribeDomainResponse;
import com.uber.cadence.DescribeTaskListRequest;
import com.uber.cadence.DescribeTaskListResponse;
import com.uber.cadence.DescribeWorkflowExecutionRequest;
import com.uber.cadence.DescribeWorkflowExecutionResponse;
import com.uber.cadence.DomainAlreadyExistsError;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.ListClosedWorkflowExecutionsRequest;
import com.uber.cadence.ListClosedWorkflowExecutionsResponse;
import com.uber.cadence.ListOpenWorkflowExecutionsRequest;
import com.uber.cadence.ListOpenWorkflowExecutionsResponse;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryFailedError;
import com.uber.cadence.QueryWorkflowRequest;
import com.uber.cadence.QueryWorkflowResponse;
import com.uber.cadence.RecordActivityTaskHeartbeatRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatResponse;
import com.uber.cadence.RegisterDomainRequest;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.RespondActivityTaskCanceledByIDRequest;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedByIDRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedByIDRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionResponse;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.UpdateDomainRequest;
import com.uber.cadence.UpdateDomainResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientInterceptor;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.internal.testservice.TestWorkflowService;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TestWorkflowEnvironmentInternal implements TestWorkflowEnvironment {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowEnvironmentInternal.class);

  private final TestEnvironmentOptions testEnvironmentOptions;
  private final WorkflowServiceWrapper service;
  private final List<Worker> workers = Collections.synchronizedList(new ArrayList<>());

  public TestWorkflowEnvironmentInternal(TestEnvironmentOptions options) {
    if (options == null) {
      this.testEnvironmentOptions = new TestEnvironmentOptions.Builder().build();
    } else {
      this.testEnvironmentOptions = options;
    }
    service = new WorkflowServiceWrapper();
    service.lockTimeSkipping();
  }

  @Override
  public Worker newWorker(String taskList) {
    Worker result =
        new Worker(
            service,
            testEnvironmentOptions.getDomain(),
            taskList,
            new WorkerOptions.Builder()
                .setInterceptorFactory(testEnvironmentOptions.getInterceptorFactory())
                .build());
    workers.add(result);
    return result;
  }

  @Override
  public WorkflowClient newWorkflowClient() {
    WorkflowClientOptions options =
        new WorkflowClientOptions.Builder()
            .setDataConverter(testEnvironmentOptions.getDataConverter())
            .setInterceptors(new TimeLockingInterceptor(service))
            .build();
    return WorkflowClientInternal.newInstance(service, testEnvironmentOptions.getDomain(), options);
  }

  @Override
  public WorkflowClient newWorkflowClient(WorkflowClientOptions options) {
    return WorkflowClientInternal.newInstance(service, testEnvironmentOptions.getDomain(), options);
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
  public String getDiagnostics() {
    StringBuilder result = new StringBuilder();
    service.getDiagnostics(result);
    return result.toString();
  }

  @Override
  public void close() {
    for (Worker w : workers) {
      w.shutdown(Duration.ofMillis(10));
    }
    service.close();
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
    public void RespondQueryTaskCompleted(RespondQueryTaskCompletedRequest completeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondQueryTaskCompleted(completeRequest);
    }

    @Override
    public QueryWorkflowResponse QueryWorkflow(QueryWorkflowRequest queryRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, QueryFailedError,
            TException {
      return impl.QueryWorkflow(queryRequest);
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
    public void RespondQueryTaskCompleted(
        RespondQueryTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.RespondQueryTaskCompleted(completeRequest, resultHandler);
    }

    @Override
    public void QueryWorkflow(QueryWorkflowRequest queryRequest, AsyncMethodCallback resultHandler)
        throws TException {
      impl.QueryWorkflow(queryRequest, resultHandler);
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
    public void RespondDecisionTaskCompleted(RespondDecisionTaskCompletedRequest completeRequest)
        throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
      impl.RespondDecisionTaskCompleted(completeRequest);
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

    public void close() {
      impl.close();
    }

    public void registerDelayedCallback(Duration delay, Runnable r) {
      impl.registerDelayedCallback(delay, r);
    }

    public void lockTimeSkipping() {
      impl.lockTimeSkipping();
    }

    public void unlockTimeSkipping() {
      impl.unlockTimeSkipping();
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
      public Optional<String> getWorkflowType() {
        return next.getWorkflowType();
      }

      @Override
      public WorkflowExecution getExecution() {
        return next.getExecution();
      }

      @Override
      public <R> R getResult(Class<R> returnType) {
        service.unlockTimeSkipping();
        try {
          return next.getResult(returnType);
        } finally {
          service.lockTimeSkipping();
        }
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(Class<R> returnType) {
        return new TimeLockingFuture<>(next.getResultAsync(returnType));
      }

      @Override
      public <R> R getResult(long timeout, TimeUnit unit, Class<R> returnType)
          throws TimeoutException {
        service.unlockTimeSkipping();
        try {
          return next.getResult(timeout, unit, returnType);
        } finally {
          service.lockTimeSkipping();
        }
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(
          long timeout, TimeUnit unit, Class<R> returnType) {
        return new TimeLockingFuture<>(next.getResultAsync(timeout, unit, returnType));
      }

      @Override
      public <R> R query(String queryType, Class<R> returnType, Object... args) {
        return next.query(queryType, returnType, args);
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
          CompletableFuture<R> ignored =
              resultAsync.whenComplete(
                  (r, e) -> {
                    service.lockTimeSkipping();
                    if (e == null) {
                      this.complete(r);
                    } else {
                      this.completeExceptionally(e);
                    }
                  });
        }

        @Override
        public R get() throws InterruptedException, ExecutionException {
          service.unlockTimeSkipping();
          return super.get();
        }

        @Override
        public R get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
          service.unlockTimeSkipping();
          try {
            return super.get(timeout, unit);
          } catch (TimeoutException | InterruptedException e) {
            service.lockTimeSkipping();
            throw e;
          }
        }

        @Override
        public R join() {
          service.unlockTimeSkipping();
          return super.join();
        }
      }
    }
  }
}
