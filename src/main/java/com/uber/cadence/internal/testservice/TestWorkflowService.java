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

package com.uber.cadence.internal.testservice;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.ClientVersionNotSupportedError;
import com.uber.cadence.CountWorkflowExecutionsRequest;
import com.uber.cadence.CountWorkflowExecutionsResponse;
import com.uber.cadence.DeprecateDomainRequest;
import com.uber.cadence.DescribeDomainRequest;
import com.uber.cadence.DescribeDomainResponse;
import com.uber.cadence.DescribeTaskListRequest;
import com.uber.cadence.DescribeTaskListResponse;
import com.uber.cadence.DescribeWorkflowExecutionRequest;
import com.uber.cadence.DescribeWorkflowExecutionResponse;
import com.uber.cadence.DomainAlreadyExistsError;
import com.uber.cadence.DomainNotActiveError;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.GetSearchAttributesResponse;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.LimitExceededError;
import com.uber.cadence.ListClosedWorkflowExecutionsRequest;
import com.uber.cadence.ListClosedWorkflowExecutionsResponse;
import com.uber.cadence.ListDomainsRequest;
import com.uber.cadence.ListDomainsResponse;
import com.uber.cadence.ListOpenWorkflowExecutionsRequest;
import com.uber.cadence.ListOpenWorkflowExecutionsResponse;
import com.uber.cadence.ListWorkflowExecutionsRequest;
import com.uber.cadence.ListWorkflowExecutionsResponse;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryFailedError;
import com.uber.cadence.QueryWorkflowRequest;
import com.uber.cadence.QueryWorkflowResponse;
import com.uber.cadence.RecordActivityTaskHeartbeatByIDRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatResponse;
import com.uber.cadence.RegisterDomainRequest;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.ResetStickyTaskListRequest;
import com.uber.cadence.ResetStickyTaskListResponse;
import com.uber.cadence.ResetWorkflowExecutionRequest;
import com.uber.cadence.ResetWorkflowExecutionResponse;
import com.uber.cadence.RespondActivityTaskCanceledByIDRequest;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedByIDRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedByIDRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedResponse;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.RetryPolicy;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.SignalExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.SignalWithStartWorkflowExecutionRequest;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionResponse;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.UpdateDomainRequest;
import com.uber.cadence.UpdateDomainResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowExecutionCloseStatus;
import com.uber.cadence.WorkflowExecutionContinuedAsNewEventAttributes;
import com.uber.cadence.WorkflowExecutionFilter;
import com.uber.cadence.WorkflowExecutionInfo;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import com.uber.cadence.internal.testservice.TestWorkflowStore.WorkflowState;
import com.uber.cadence.serviceclient.IWorkflowService;
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
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory implementation of the Cadence service. To be used for testing purposes only. Do not use
 * directly. Instead use {@link com.uber.cadence.testing.TestWorkflowEnvironment}.
 */
public final class TestWorkflowService implements IWorkflowService {

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

  private TestWorkflowMutableState getMutableState(ExecutionId executionId)
      throws InternalServiceError, EntityNotExistsError {
    return getMutableState(executionId, true);
  }

  private TestWorkflowMutableState getMutableState(ExecutionId executionId, boolean failNotExists)
      throws InternalServiceError, EntityNotExistsError {
    lock.lock();
    try {
      if (executionId.getExecution().getRunId() == null) {
        return getMutableState(executionId.getWorkflowId(), failNotExists);
      }
      TestWorkflowMutableState mutableState = executions.get(executionId);
      if (mutableState == null && failNotExists) {
        throw new InternalServiceError("Execution not found in mutable state: " + executionId);
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  private TestWorkflowMutableState getMutableState(WorkflowId workflowId)
      throws EntityNotExistsError {
    return getMutableState(workflowId, true);
  }

  private TestWorkflowMutableState getMutableState(WorkflowId workflowId, boolean failNotExists)
      throws EntityNotExistsError {
    lock.lock();
    try {
      TestWorkflowMutableState mutableState = executionsByWorkflowId.get(workflowId);
      if (mutableState == null && failNotExists) {
        throw new EntityNotExistsError("Execution not found in mutable state: " + workflowId);
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void RegisterDomain(RegisterDomainRequest registerRequest)
      throws BadRequestError, InternalServiceError, DomainAlreadyExistsError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public DescribeDomainResponse DescribeDomain(DescribeDomainRequest describeRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ListDomainsResponse ListDomains(ListDomainsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public UpdateDomainResponse UpdateDomain(UpdateDomainRequest updateRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DeprecateDomain(DeprecateDomainRequest deprecateRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public StartWorkflowExecutionResponse StartWorkflowExecution(
      StartWorkflowExecutionRequest startRequest) throws TException {
    return startWorkflowExecutionImpl(
        startRequest, 0, Optional.empty(), OptionalLong.empty(), Optional.empty());
  }

  StartWorkflowExecutionResponse startWorkflowExecutionImpl(
      StartWorkflowExecutionRequest startRequest,
      int backoffStartIntervalInSeconds,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<SignalWorkflowExecutionRequest> signalWithStartSignal)
      throws BadRequestError, WorkflowExecutionAlreadyStartedError, InternalServiceError {
    String requestWorkflowId = requireNotNull("WorkflowId", startRequest.getWorkflowId());
    String domain = requireNotNull("Domain", startRequest.getDomain());
    WorkflowId workflowId = new WorkflowId(domain, requestWorkflowId);
    TestWorkflowMutableState existing;
    lock.lock();
    try {
      existing = executionsByWorkflowId.get(workflowId);
      if (existing != null) {
        Optional<WorkflowExecutionCloseStatus> statusOptional = existing.getCloseStatus();
        WorkflowIdReusePolicy policy =
            startRequest.isSetWorkflowIdReusePolicy()
                ? startRequest.getWorkflowIdReusePolicy()
                : WorkflowIdReusePolicy.AllowDuplicateFailedOnly;
        if (!statusOptional.isPresent() || policy == WorkflowIdReusePolicy.RejectDuplicate) {
          return throwDuplicatedWorkflow(startRequest, existing);
        }
        WorkflowExecutionCloseStatus status = statusOptional.get();
        if (policy == WorkflowIdReusePolicy.AllowDuplicateFailedOnly
            && (status == WorkflowExecutionCloseStatus.COMPLETED
                || status == WorkflowExecutionCloseStatus.CONTINUED_AS_NEW)) {
          return throwDuplicatedWorkflow(startRequest, existing);
        }
      }
      RetryPolicy retryPolicy = startRequest.getRetryPolicy();
      Optional<RetryState> retryState = newRetryStateLocked(retryPolicy);
      return startWorkflowExecutionNoRunningCheckLocked(
          startRequest,
          false,
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

  private Optional<RetryState> newRetryStateLocked(RetryPolicy retryPolicy) throws BadRequestError {
    if (retryPolicy == null) {
      return Optional.empty();
    }
    long expirationInterval =
        TimeUnit.SECONDS.toMillis(retryPolicy.getExpirationIntervalInSeconds());
    long expirationTime = store.currentTimeMillis() + expirationInterval;
    return Optional.of(new RetryState(retryPolicy, expirationTime));
  }

  private StartWorkflowExecutionResponse throwDuplicatedWorkflow(
      StartWorkflowExecutionRequest startRequest, TestWorkflowMutableState existing)
      throws WorkflowExecutionAlreadyStartedError {
    WorkflowExecutionAlreadyStartedError error = new WorkflowExecutionAlreadyStartedError();
    WorkflowExecution execution = existing.getExecutionId().getExecution();
    error.setMessage(
        String.format(
            "WorkflowId: %s, " + "RunId: %s", execution.getWorkflowId(), execution.getRunId()));
    error.setRunId(execution.getRunId());
    error.setStartRequestId(startRequest.getRequestId());
    throw error;
  }

  private StartWorkflowExecutionResponse startWorkflowExecutionNoRunningCheckLocked(
      StartWorkflowExecutionRequest startRequest,
      boolean continuedAsNew,
      Optional<RetryState> retryState,
      int backoffStartIntervalInSeconds,
      byte[] lastCompletionResult,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      Optional<SignalWorkflowExecutionRequest> signalWithStartSignal,
      WorkflowId workflowId)
      throws InternalServiceError, BadRequestError {
    String domain = startRequest.getDomain();
    TestWorkflowMutableState mutableState =
        new TestWorkflowMutableStateImpl(
            startRequest,
            retryState,
            backoffStartIntervalInSeconds,
            lastCompletionResult,
            parent,
            parentChildInitiatedEventId,
            this,
            store);
    WorkflowExecution execution = mutableState.getExecutionId().getExecution();
    ExecutionId executionId = new ExecutionId(domain, execution);
    executionsByWorkflowId.put(workflowId, mutableState);
    executions.put(executionId, mutableState);
    mutableState.startWorkflow(continuedAsNew, signalWithStartSignal);
    return new StartWorkflowExecutionResponse().setRunId(execution.getRunId());
  }

  @Override
  public GetWorkflowExecutionHistoryResponse GetWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest getRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    ExecutionId executionId = new ExecutionId(getRequest.getDomain(), getRequest.getExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);

    return store.getWorkflowExecutionHistory(mutableState.getExecutionId(), getRequest);
  }

  @Override
  public PollForDecisionTaskResponse PollForDecisionTask(PollForDecisionTaskRequest pollRequest)
      throws BadRequestError, InternalServiceError, ServiceBusyError, TException {
    PollForDecisionTaskResponse task;
    try {
      task = store.pollForDecisionTask(pollRequest);
    } catch (InterruptedException e) {
      return new PollForDecisionTaskResponse();
    }
    ExecutionId executionId = new ExecutionId(pollRequest.getDomain(), task.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    try {
      mutableState.startDecisionTask(task, pollRequest);
      // The task always has the original tasklist is was created on as part of the response. This
      // may different
      // then the task list it was scheduled on as in the case of sticky execution.
      task.setWorkflowExecutionTaskList(mutableState.getStartRequest().taskList);
      return task;
    } catch (EntityNotExistsError e) {
      if (log.isDebugEnabled()) {
        log.debug("Skipping outdated decision task for " + executionId, e);
      }
      // skip the task
    }
    task.setWorkflowExecutionTaskList(mutableState.getStartRequest().taskList);
    return task;
  }

  @Override
  public RespondDecisionTaskCompletedResponse RespondDecisionTaskCompleted(
      RespondDecisionTaskCompletedRequest request)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    DecisionTaskToken taskToken = DecisionTaskToken.fromBytes(request.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
    mutableState.completeDecisionTask(taskToken.getHistorySize(), request);
    return new RespondDecisionTaskCompletedResponse();
  }

  @Override
  public void RespondDecisionTaskFailed(RespondDecisionTaskFailedRequest failedRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    DecisionTaskToken taskToken = DecisionTaskToken.fromBytes(failedRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
    mutableState.failDecisionTask(failedRequest);
  }

  @Override
  public PollForActivityTaskResponse PollForActivityTask(PollForActivityTaskRequest pollRequest)
      throws BadRequestError, InternalServiceError, ServiceBusyError, TException {
    PollForActivityTaskResponse task;
    while (true) {
      try {
        task = store.pollForActivityTask(pollRequest);
      } catch (InterruptedException e) {
        return new PollForActivityTaskResponse();
      }
      ExecutionId executionId =
          new ExecutionId(pollRequest.getDomain(), task.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      try {
        mutableState.startActivityTask(task, pollRequest);
        return task;
      } catch (EntityNotExistsError e) {
        if (log.isDebugEnabled()) {
          log.debug("Skipping outdated activity task for " + executionId, e);
        }
      }
    }
  }

  @Override
  public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    ActivityId activityId = ActivityId.fromBytes(heartbeatRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    return mutableState.heartbeatActivityTask(activityId.getId(), heartbeatRequest.getDetails());
  }

  @Override
  public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeatByID(
      RecordActivityTaskHeartbeatByIDRequest heartbeatRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, DomainNotActiveError,
          LimitExceededError, ServiceBusyError, TException {
    ExecutionId execution =
        new ExecutionId(
            heartbeatRequest.getDomain(),
            heartbeatRequest.getWorkflowID(),
            heartbeatRequest.getRunID());
    TestWorkflowMutableState mutableState = getMutableState(execution);
    return mutableState.heartbeatActivityTask(
        heartbeatRequest.getActivityID(), heartbeatRequest.getDetails());
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

  // TODO: https://github.com/uber/cadence-java-client/issues/359
  @Override
  public ResetWorkflowExecutionResponse ResetWorkflowExecution(
      ResetWorkflowExecutionRequest resetRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          DomainNotActiveError, LimitExceededError, ClientVersionNotSupportedError, TException {
    return null;
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
            .setCronSchedule(previousRunStartRequest.getCronSchedule())
            .setChildPolicy(previousRunStartRequest.getChildPolicy());
    if (a.isSetInput()) {
      startRequest.setInput(a.getInput());
    }
    lock.lock();
    try {
      StartWorkflowExecutionResponse response =
          startWorkflowExecutionNoRunningCheckLocked(
              startRequest,
              true,
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
