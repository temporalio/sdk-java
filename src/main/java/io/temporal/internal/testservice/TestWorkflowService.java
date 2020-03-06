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

package io.temporal.internal.testservice;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.temporal.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import io.temporal.internal.testservice.TestWorkflowStore.WorkflowState;
import io.temporal.proto.common.RetryPolicy;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.enums.WorkflowExecutionCloseStatus;
import io.temporal.proto.enums.WorkflowIdReusePolicy;
import io.temporal.proto.failure.WorkflowExecutionAlreadyStarted;
import io.temporal.proto.workflowservice.DeprecateDomainRequest;
import io.temporal.proto.workflowservice.DeprecateDomainResponse;
import io.temporal.proto.workflowservice.DescribeDomainRequest;
import io.temporal.proto.workflowservice.DescribeDomainResponse;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryResponse;
import io.temporal.proto.workflowservice.ListDomainsRequest;
import io.temporal.proto.workflowservice.ListDomainsResponse;
import io.temporal.proto.workflowservice.PollForActivityTaskRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatByIDRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatByIDResponse;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatResponse;
import io.temporal.proto.workflowservice.RegisterDomainRequest;
import io.temporal.proto.workflowservice.RegisterDomainResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedResponse;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedResponse;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.UpdateDomainRequest;
import io.temporal.proto.workflowservice.UpdateDomainResponse;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import io.temporal.serviceclient.GrpcStatusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In memory implementation of the Temporal service. To be used for testing purposes only. Do not
 * use directly. Instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
public final class TestWorkflowService extends WorkflowServiceGrpc.WorkflowServiceImplBase {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowService.class);

  private final Lock lock = new ReentrantLock();

  private final TestWorkflowStore store = new TestWorkflowStoreImpl();

  private final Map<ExecutionId, TestWorkflowMutableState> executions = new HashMap<>();

  // key->WorkflowId
  private final Map<WorkflowId, TestWorkflowMutableState> executionsByWorkflowId = new HashMap<>();

  private final ForkJoinPool forkJoinPool = new ForkJoinPool(4);

  @Override
  public void close() {
    store.close();
  }

  private TestWorkflowMutableState getMutableState(ExecutionId executionId) {
    return getMutableState(executionId, true);
  }

  private TestWorkflowMutableState getMutableState(ExecutionId executionId, boolean failNotExists) {
    lock.lock();
    try {
      if (executionId.getExecution().getRunId() == null) {
        return getMutableState(executionId.getWorkflowId(), failNotExists);
      }
      TestWorkflowMutableState mutableState = executions.get(executionId);
      if (mutableState == null && failNotExists) {
        throw Status.INTERNAL
            .withDescription("Execution not found in mutable state: " + executionId)
            .asRuntimeException();
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  private TestWorkflowMutableState getMutableState(WorkflowId workflowId) {
    return getMutableState(workflowId, true);
  }

  private TestWorkflowMutableState getMutableState(WorkflowId workflowId, boolean failNotExists) {
    lock.lock();
    try {
      TestWorkflowMutableState mutableState = executionsByWorkflowId.get(workflowId);
      if (mutableState == null && failNotExists) {
        throw Status.INTERNAL
            .withDescription("Execution not found in mutable state: " + workflowId)
            .asRuntimeException();
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void registerDomain(
      RegisterDomainRequest request, StreamObserver<RegisterDomainResponse> responseObserver) {
    super.registerDomain(request, responseObserver);
  }

  @Override
  public void describeDomain(
      DescribeDomainRequest request, StreamObserver<DescribeDomainResponse> responseObserver) {
    super.describeDomain(request, responseObserver);
  }

  @Override
  public void listDomains(
      ListDomainsRequest request, StreamObserver<ListDomainsResponse> responseObserver) {
    super.listDomains(request, responseObserver);
  }

  @Override
  public void updateDomain(
      UpdateDomainRequest request, StreamObserver<UpdateDomainResponse> responseObserver) {
    super.updateDomain(request, responseObserver);
  }

  @Override
  public void deprecateDomain(
      DeprecateDomainRequest request, StreamObserver<DeprecateDomainResponse> responseObserver) {
    super.deprecateDomain(request, responseObserver);
  }

  @Override
  public void startWorkflowExecution(
      StartWorkflowExecutionRequest request,
      StreamObserver<StartWorkflowExecutionResponse> responseObserver) {
    StartWorkflowExecutionResponse response =
        startWorkflowExecutionImpl(
            request, 0, Optional.empty(), OptionalLong.empty(), Optional.empty());
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  StartWorkflowExecutionResponse startWorkflowExecutionImpl(
      StartWorkflowExecutionRequest startRequest,
      int backoffStartIntervalInSeconds,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<SignalWorkflowExecutionRequest> signalWithStartSignal) {
    String requestWorkflowId = requireNotNull("WorkflowId", startRequest.getWorkflowId());
    String domain = requireNotNull("Domain", startRequest.getDomain());
    WorkflowId workflowId = new WorkflowId(domain, requestWorkflowId);
    TestWorkflowMutableState existing;
    lock.lock();
    try {
      existing = executionsByWorkflowId.get(workflowId);
      if (existing != null) {
        Optional<WorkflowExecutionCloseStatus> statusOptional = existing.getCloseStatus();
        WorkflowIdReusePolicy policy = startRequest.getWorkflowIdReusePolicy();
        if (!statusOptional.isPresent()
            || policy == WorkflowIdReusePolicy.WorkflowIdReusePolicyRejectDuplicate) {
          return throwDuplicatedWorkflow(startRequest, existing);
        }
        WorkflowExecutionCloseStatus status = statusOptional.get();
        if (policy == WorkflowIdReusePolicy.WorkflowIdReusePolicyAllowDuplicateFailedOnly
            && (status == WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusCompleted
                || status
                    == WorkflowExecutionCloseStatus.WorkflowExecutionCloseStatusContinuedAsNew)) {
          return throwDuplicatedWorkflow(startRequest, existing);
        }
      }
      Optional<RetryState> retryState;
      if (startRequest.hasRetryPolicy()) {
        retryState = newRetryStateLocked(startRequest.getRetryPolicy());
      } else {
        retryState = Optional.empty();
      }
      return startWorkflowExecutionNoRunningCheckLocked(
          startRequest,
          Optional.empty(),
          retryState,
          backoffStartIntervalInSeconds,
          null,
          parent,
          parentChildInitiatedEventId,
          signalWithStartSignal,
          workflowId);
    } finally {
      lock.unlock();
    }
  }

  private Optional<RetryState> newRetryStateLocked(RetryPolicy retryPolicy) {
    if (retryPolicy == null) {
      return Optional.empty();
    }
    long expirationInterval =
        TimeUnit.SECONDS.toMillis(retryPolicy.getExpirationIntervalInSeconds());
    long expirationTime = store.currentTimeMillis() + expirationInterval;
    return Optional.of(new RetryState(retryPolicy, expirationTime));
  }

  private StartWorkflowExecutionResponse throwDuplicatedWorkflow(
      StartWorkflowExecutionRequest startRequest, TestWorkflowMutableState existing) {
    WorkflowExecution execution = existing.getExecutionId().getExecution();
    WorkflowExecutionAlreadyStarted error =
        WorkflowExecutionAlreadyStarted.newBuilder()
            .setRunId(execution.getRunId())
            .setStartRequestId(startRequest.getRequestId())
            .build();
    throw GrpcStatusUtils.newException(
        Status.ALREADY_EXISTS.withDescription(
            String.format(
                "WorkflowId: %s, " + "RunId: %s", execution.getWorkflowId(), execution.getRunId())),
        error);
  }

  private StartWorkflowExecutionResponse startWorkflowExecutionNoRunningCheckLocked(
      StartWorkflowExecutionRequest startRequest,
      Optional<String> continuedExecutionRunId,
      Optional<RetryState> retryState,
      int backoffStartIntervalInSeconds,
      byte[] lastCompletionResult,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<SignalWorkflowExecutionRequest> signalWithStartSignal,
      WorkflowId workflowId) {
    String domain = startRequest.getDomain();
    TestWorkflowMutableState mutableState =
        new TestWorkflowMutableStateImpl(
            startRequest,
            retryState,
            backoffStartIntervalInSeconds,
            lastCompletionResult,
            parent,
            parentChildInitiatedEventId,
            continuedExecutionRunId,
            this,
            store);
    WorkflowExecution execution = mutableState.getExecutionId().getExecution();
    ExecutionId executionId = new ExecutionId(domain, execution);
    executionsByWorkflowId.put(workflowId, mutableState);
    executions.put(executionId, mutableState);
    mutableState.startWorkflow(continuedExecutionRunId.isPresent(), signalWithStartSignal);
    return StartWorkflowExecutionResponse.newBuilder().setRunId(execution.getRunId()).build();
  }

  @Override
  public void getWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest getRequest,
      StreamObserver<GetWorkflowExecutionHistoryResponse> responseObserver) {
    ExecutionId executionId = new ExecutionId(getRequest.getDomain(), getRequest.getExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    responseObserver.onNext(
        store.getWorkflowExecutionHistory(mutableState.getExecutionId(), getRequest));
    responseObserver.onCompleted();
  }

  @Override
  public void pollForDecisionTask(
      PollForDecisionTaskRequest pollRequest,
      StreamObserver<PollForDecisionTaskResponse> responseObserver) {
    PollForDecisionTaskResponse.Builder task;
    try {
      task = store.pollForDecisionTask(pollRequest);
    } catch (InterruptedException e) {
      responseObserver.onNext(PollForDecisionTaskResponse.getDefaultInstance());
      responseObserver.onCompleted();
      return;
    }
    ExecutionId executionId = new ExecutionId(pollRequest.getDomain(), task.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    try {
      mutableState.startDecisionTask(task, pollRequest);
      // The task always has the original tasklist is was created on as part of the response. This
      // may different
      // then the task list it was scheduled on as in the case of sticky execution.
      task.setWorkflowExecutionTaskList(mutableState.getStartRequest().getTaskList());
      responseObserver.onNext(task.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus() == Status.NOT_FOUND) {
        if (log.isDebugEnabled()) {
          log.debug("Skipping outdated decision task for " + executionId, e);
        }
        // skip the task
      } else {
        responseObserver.onError(e);
      }
    }
    responseObserver.onCompleted();
  }

  @Override
  public void respondDecisionTaskCompleted(
      RespondDecisionTaskCompletedRequest request,
      StreamObserver<RespondDecisionTaskCompletedResponse> responseObserver) {
    DecisionTaskToken taskToken = DecisionTaskToken.fromBytes(request.getTaskToken().toByteArray());
    TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
    mutableState.completeDecisionTask(taskToken.getHistorySize(), request);
    responseObserver.onNext(RespondDecisionTaskCompletedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondDecisionTaskFailed(
      RespondDecisionTaskFailedRequest failedRequest,
      StreamObserver<RespondDecisionTaskFailedResponse> responseObserver) {
    DecisionTaskToken taskToken =
        DecisionTaskToken.fromBytes(failedRequest.getTaskToken().toByteArray());
    TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
    mutableState.failDecisionTask(failedRequest);
    responseObserver.onNext(RespondDecisionTaskFailedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void pollForActivityTask(
      PollForActivityTaskRequest pollRequest,
      StreamObserver<PollForActivityTaskResponse> responseObserver) {
    PollForActivityTaskResponse task;
    while (true) {
      try {
        task = store.pollForActivityTask(pollRequest);
      } catch (InterruptedException e) {
        responseObserver.onNext(PollForActivityTaskResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      }
      ExecutionId executionId =
          new ExecutionId(pollRequest.getDomain(), task.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      try {
        mutableState.startActivityTask(task, pollRequest);
        responseObserver.onNext(task);
      } catch (StatusRuntimeException e) {
        if (e.getStatus() == Status.NOT_FOUND) {
          if (log.isDebugEnabled()) {
            log.debug("Skipping outdated activity task for " + executionId, e);
          }
        } else {
          responseObserver.onError(e);
        }
      }
      responseObserver.onCompleted();
    }
  }

  @Override
  public void recordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest,
      StreamObserver<RecordActivityTaskHeartbeatResponse> responseObserver) {
    ActivityId activityId = ActivityId.fromBytes(heartbeatRequest.getTaskToken().toByteArray());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    boolean cancelRequested =
        mutableState.heartbeatActivityTask(
            activityId.getId(), heartbeatRequest.getDetails().toByteArray());
    responseObserver.onNext(
        RecordActivityTaskHeartbeatResponse.newBuilder()
            .setCancelRequested(cancelRequested)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void recordActivityTaskHeartbeatByID(
      RecordActivityTaskHeartbeatByIDRequest heartbeatRequest,
      StreamObserver<RecordActivityTaskHeartbeatByIDResponse> responseObserver) {
    ExecutionId execution =
        new ExecutionId(
            heartbeatRequest.getDomain(),
            heartbeatRequest.getWorkflowID(),
            heartbeatRequest.getRunID());
    TestWorkflowMutableState mutableState = getMutableState(execution);
    boolean cancelRequested =
        mutableState.heartbeatActivityTask(
            heartbeatRequest.getActivityID(), heartbeatRequest.getDetails().toByteArray());
    responseObserver.onNext(
        RecordActivityTaskHeartbeatByIDResponse.newBuilder()
            .setCancelRequested(cancelRequested)
            .build());
  }

  @Override
  public void RespondActivityTaskCompleted(RespondActivityTaskCompletedRequest completeRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId = ActivityId.fromBytes(completeRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    mutableState.completeActivityTask(activityId.getId(), completeRequest);
  }

  @Override
  public void RespondActivityTaskCompletedByID(
      RespondActivityTaskCompletedByIDRequest completeRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId =
        new ActivityId(
            completeRequest.getDomain(),
            completeRequest.getWorkflowID(),
            completeRequest.getRunID(),
            completeRequest.getActivityID());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
    mutableState.completeActivityTaskById(activityId.getId(), completeRequest);
  }

  @Override
  public void RespondActivityTaskFailed(RespondActivityTaskFailedRequest failRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId = ActivityId.fromBytes(failRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    mutableState.failActivityTask(activityId.getId(), failRequest);
  }

  @Override
  public void RespondActivityTaskFailedByID(RespondActivityTaskFailedByIDRequest failRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId =
        new ActivityId(
            failRequest.getDomain(),
            failRequest.getWorkflowID(),
            failRequest.getRunID(),
            failRequest.getActivityID());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
    mutableState.failActivityTaskById(activityId.getId(), failRequest);
  }

  @Override
  public void RespondActivityTaskCanceled(RespondActivityTaskCanceledRequest canceledRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId = ActivityId.fromBytes(canceledRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    mutableState.cancelActivityTask(activityId.getId(), canceledRequest);
  }

  @Override
  public void RespondActivityTaskCanceledByID(
      RespondActivityTaskCanceledByIDRequest canceledRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId =
        new ActivityId(
            canceledRequest.getDomain(),
            canceledRequest.getWorkflowID(),
            canceledRequest.getRunID(),
            canceledRequest.getActivityID());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
    mutableState.cancelActivityTaskById(activityId.getId(), canceledRequest);
  }

  @Override
  public void RequestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest)
      throws TException {
    ExecutionId executionId =
        new ExecutionId(cancelRequest.getDomain(), cancelRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.requestCancelWorkflowExecution(cancelRequest);
  }

  @Override
  public void SignalWorkflowExecution(SignalWorkflowExecutionRequest signalRequest)
      throws TException {
    ExecutionId executionId =
        new ExecutionId(signalRequest.getDomain(), signalRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.signal(signalRequest);
  }

  @Override
  public StartWorkflowExecutionResponse SignalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest r)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          DomainNotActiveError, LimitExceededError, WorkflowExecutionAlreadyStartedError,
          TException {
    ExecutionId executionId = new ExecutionId(r.getDomain(), r.getWorkflowId(), null);
    TestWorkflowMutableState mutableState = getMutableState(executionId, false);
    SignalWorkflowExecutionRequest signalRequest =
        new SignalWorkflowExecutionRequest()
            .setInput(r.getSignalInput())
            .setSignalName(r.getSignalName())
            .setControl(r.getControl())
            .setDomain(r.getDomain())
            .setWorkflowExecution(executionId.getExecution())
            .setRequestId(r.getRequestId())
            .setIdentity(r.getIdentity());
    if (mutableState != null) {
      mutableState.signal(signalRequest);
      return new StartWorkflowExecutionResponse()
          .setRunId(mutableState.getExecutionId().getExecution().getRunId());
    }
    StartWorkflowExecutionRequest startRequest =
        new StartWorkflowExecutionRequest()
            .setInput(r.getInput())
            .setExecutionStartToCloseTimeoutSeconds(r.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(r.getTaskStartToCloseTimeoutSeconds())
            .setDomain(r.getDomain())
            .setRetryPolicy(r.getRetryPolicy())
            .setTaskList(r.getTaskList())
            .setWorkflowId(r.getWorkflowId())
            .setWorkflowIdReusePolicy(r.getWorkflowIdReusePolicy())
            .setWorkflowType(r.getWorkflowType())
            .setCronSchedule(r.getCronSchedule())
            .setRequestId(r.getRequestId())
            .setIdentity(r.getIdentity());
    return startWorkflowExecutionImpl(
        startRequest, 0, Optional.empty(), OptionalLong.empty(), Optional.of(signalRequest));
  }

  // TODO: https://github.io.temporal-java-client/issues/359
  @Override
  public ResetWorkflowExecutionResponse ResetWorkflowExecution(
      ResetWorkflowExecutionRequest resetRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          DomainNotActiveError, LimitExceededError, ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  public void signalExternalWorkflowExecution(
      String signalId,
      SignalExternalWorkflowExecutionDecisionAttributes a,
      TestWorkflowMutableState source)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    ExecutionId executionId = new ExecutionId(a.getDomain(), a.getExecution());
    TestWorkflowMutableState mutableState = null;
    try {
      mutableState = getMutableState(executionId);
      mutableState.signalFromWorkflow(a);
      source.completeSignalExternalWorkflowExecution(
          signalId, mutableState.getExecutionId().getExecution().getRunId());
    } catch (EntityNotExistsError entityNotExistsError) {
      source.failSignalExternalWorkflowExecution(
          signalId, SignalExternalWorkflowExecutionFailedCause.UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION);
    }
  }

  @Override
  public void TerminateWorkflowExecution(TerminateWorkflowExecutionRequest terminateRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    throw new UnsupportedOperationException("not implemented");
  }

  /**
   * Creates next run of a workflow execution
   *
   * @return RunId
   */
  public String continueAsNew(
      StartWorkflowExecutionRequest previousRunStartRequest,
      WorkflowExecutionContinuedAsNewEventAttributes a,
      Optional<RetryState> retryState,
      String identity,
      ExecutionId executionId,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId)
      throws InternalServiceError, BadRequestError {
    StartWorkflowExecutionRequest startRequest =
        new StartWorkflowExecutionRequest()
            .setWorkflowType(a.getWorkflowType())
            .setExecutionStartToCloseTimeoutSeconds(a.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(a.getTaskStartToCloseTimeoutSeconds())
            .setDomain(executionId.getDomain())
            .setTaskList(a.getTaskList())
            .setWorkflowId(executionId.getWorkflowId().getWorkflowId())
            .setWorkflowIdReusePolicy(previousRunStartRequest.getWorkflowIdReusePolicy())
            .setIdentity(identity)
            .setRetryPolicy(previousRunStartRequest.getRetryPolicy())
            .setCronSchedule(previousRunStartRequest.getCronSchedule());
    if (a.isSetInput()) {
      startRequest.setInput(a.getInput());
    }
    lock.lock();
    try {
      StartWorkflowExecutionResponse response =
          startWorkflowExecutionNoRunningCheckLocked(
              startRequest,
              Optional.of(executionId.getExecution().getRunId()),
              retryState,
              a.getBackoffStartIntervalInSeconds(),
              a.getLastCompletionResult(),
              parent,
              parentChildInitiatedEventId,
              Optional.empty(),
              executionId.getWorkflowId());
      return response.getRunId();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ListOpenWorkflowExecutionsResponse ListOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    Optional<String> workflowIdFilter;
    WorkflowExecutionFilter executionFilter = listRequest.getExecutionFilter();
    if (executionFilter != null
        && executionFilter.isSetWorkflowId()
        && !executionFilter.getWorkflowId().isEmpty()) {
      workflowIdFilter = Optional.of(executionFilter.getWorkflowId());
    } else {
      workflowIdFilter = Optional.empty();
    }
    List<WorkflowExecutionInfo> result = store.listWorkflows(WorkflowState.OPEN, workflowIdFilter);
    return new ListOpenWorkflowExecutionsResponse().setExecutions(result);
  }

  @Override
  public ListClosedWorkflowExecutionsResponse ListClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    Optional<String> workflowIdFilter;
    WorkflowExecutionFilter executionFilter = listRequest.getExecutionFilter();
    if (executionFilter != null
        && executionFilter.isSetWorkflowId()
        && !executionFilter.getWorkflowId().isEmpty()) {
      workflowIdFilter = Optional.of(executionFilter.getWorkflowId());
    } else {
      workflowIdFilter = Optional.empty();
    }
    List<WorkflowExecutionInfo> result =
        store.listWorkflows(WorkflowState.CLOSED, workflowIdFilter);
    return new ListClosedWorkflowExecutionsResponse().setExecutions(result);
  }

  @Override
  public ListWorkflowExecutionsResponse ListWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ListArchivedWorkflowExecutionsResponse ListArchivedWorkflowExecutions(
      ListArchivedWorkflowExecutionsRequest listRequest)
      throws BadRequestError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ListWorkflowExecutionsResponse ScanWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public CountWorkflowExecutionsResponse CountWorkflowExecutions(
      CountWorkflowExecutionsRequest countRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public GetSearchAttributesResponse GetSearchAttributes()
      throws InternalServiceError, ServiceBusyError, ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondQueryTaskCompleted(RespondQueryTaskCompletedRequest completeRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    QueryId queryId = QueryId.fromBytes(completeRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(queryId.getExecutionId());
    mutableState.completeQuery(queryId, completeRequest);
  }

  @Override
  public ResetStickyTaskListResponse ResetStickyTaskList(ResetStickyTaskListRequest resetRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, LimitExceededError,
          ServiceBusyError, DomainNotActiveError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public QueryWorkflowResponse QueryWorkflow(QueryWorkflowRequest queryRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, QueryFailedError,
          TException {
    ExecutionId executionId =
        new ExecutionId(queryRequest.getDomain(), queryRequest.getExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    return mutableState.query(queryRequest);
  }

  @Override
  public GetWorkflowExecutionRawHistoryResponse GetWorkflowExecutionRawHistory(
      GetWorkflowExecutionRawHistoryRequest getRequest)
      throws BadRequestError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public DescribeWorkflowExecutionResponse DescribeWorkflowExecution(
      DescribeWorkflowExecutionRequest describeRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public DescribeTaskListResponse DescribeTaskList(DescribeTaskListRequest request)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ClusterInfo GetClusterInfo() throws InternalServiceError, ServiceBusyError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ListTaskListPartitionsResponse ListTaskListPartitions(
      ListTaskListPartitionsRequest request)
      throws BadRequestError, EntityNotExistsError, LimitExceededError, ServiceBusyError,
          TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public PollForWorkflowExecutionRawHistoryResponse PollForWorkflowExecutionRawHistory(
      PollForWorkflowExecutionRawHistoryRequest getRequest)
      throws BadRequestError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, CurrentBranchChangedError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RegisterDomain(
      RegisterDomainRequest registerRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DescribeDomain(
      DescribeDomainRequest describeRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListDomains(ListDomainsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void UpdateDomain(UpdateDomainRequest updateRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DeprecateDomain(
      DeprecateDomainRequest deprecateRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void StartWorkflowExecution(
      StartWorkflowExecutionRequest startRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @SuppressWarnings("unchecked") // Generator ignores that AsyncMethodCallback is generic
  @Override
  public void GetWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest getRequest, AsyncMethodCallback resultHandler)
      throws TException {
    forkJoinPool.execute(
        () -> {
          try {
            GetWorkflowExecutionHistoryResponse result = GetWorkflowExecutionHistory(getRequest);
            resultHandler.onComplete(result);
          } catch (TException e) {
            resultHandler.onError(e);
          }
        });
  }

  @Override
  public void PollForDecisionTask(
      PollForDecisionTaskRequest pollRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondDecisionTaskCompleted(
      RespondDecisionTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondDecisionTaskFailed(
      RespondDecisionTaskFailedRequest failedRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void PollForActivityTask(
      PollForActivityTaskRequest pollRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RecordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RecordActivityTaskHeartbeatByID(
      RecordActivityTaskHeartbeatByIDRequest heartbeatRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCompleted(
      RespondActivityTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCompletedByID(
      RespondActivityTaskCompletedByIDRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskFailed(
      RespondActivityTaskFailedRequest failRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskFailedByID(
      RespondActivityTaskFailedByIDRequest failRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCanceled(
      RespondActivityTaskCanceledRequest canceledRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCanceledByID(
      RespondActivityTaskCanceledByIDRequest canceledRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RequestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void SignalWorkflowExecution(
      SignalWorkflowExecutionRequest signalRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void SignalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest signalWithStartRequest,
      AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ResetWorkflowExecution(
      ResetWorkflowExecutionRequest resetRequest, AsyncMethodCallback resultHandler)
      throws TException {}

  @Override
  public void TerminateWorkflowExecution(
      TerminateWorkflowExecutionRequest terminateRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListArchivedWorkflowExecutions(
      ListArchivedWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {}

  @Override
  public void ScanWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void CountWorkflowExecutions(
      CountWorkflowExecutionsRequest countRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void GetSearchAttributes(AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondQueryTaskCompleted(
      RespondQueryTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ResetStickyTaskList(
      ResetStickyTaskListRequest resetRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void QueryWorkflow(QueryWorkflowRequest queryRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void GetWorkflowExecutionRawHistory(
      GetWorkflowExecutionRawHistoryRequest getRequest, AsyncMethodCallback resultHandler)
      throws TException {}

  @Override
  public void DescribeWorkflowExecution(
      DescribeWorkflowExecutionRequest describeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DescribeTaskList(DescribeTaskListRequest request, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void GetClusterInfo(AsyncMethodCallback resultHandler) throws TException {}

  @Override
  public void ListTaskListPartitions(
      ListTaskListPartitionsRequest request, AsyncMethodCallback resultHandler) throws TException {}

  @Override
  public void PollForWorkflowExecutionRawHistory(
      PollForWorkflowExecutionRawHistoryRequest getRequest, AsyncMethodCallback resultHandler)
      throws TException {}

  private <R> R requireNotNull(String fieldName, R value) throws BadRequestError {
    if (value == null) {
      throw new BadRequestError("Missing requried field \"" + fieldName + "\".");
    }
    return value;
  }

  /**
   * Adds diagnostic data about internal service state to the provided {@link StringBuilder}.
   * Currently includes histories of all workflow instances stored in the service.
   */
  public void getDiagnostics(StringBuilder result) {
    store.getDiagnostics(result);
  }

  public long currentTimeMillis() {
    return store.getTimer().getClock().getAsLong();
  }

  /** Invokes callback after the specified delay according to internal service clock. */
  public void registerDelayedCallback(Duration delay, Runnable r) {
    store.registerDelayedCallback(delay, r);
  }

  /**
   * Disables time skipping. To enable back call {@link #unlockTimeSkipping(String)}. These calls
   * are counted, so calling unlock does not guarantee that time is going to be skipped immediately
   * as another lock can be holding it.
   */
  public void lockTimeSkipping(String caller) {
    store.getTimer().lockTimeSkipping(caller);
  }

  public void unlockTimeSkipping(String caller) {
    store.getTimer().unlockTimeSkipping(caller);
  }

  /**
   * Blocks calling thread until internal clock doesn't pass the current + duration time. Might not
   * block at all due to time skipping.
   */
  public void sleep(Duration duration) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    store
        .getTimer()
        .schedule(
            duration,
            () -> {
              store.getTimer().lockTimeSkipping("TestWorkflowService sleep");
              result.complete(null);
            },
            "workflow sleep");
    store.getTimer().unlockTimeSkipping("TestWorkflowService sleep");
    try {
      result.get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }
}
