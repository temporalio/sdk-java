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
import com.uber.cadence.SignalExternalWorkflowExecutionDecisionAttributes;
import com.uber.cadence.SignalExternalWorkflowExecutionFailedCause;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionResponse;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.UpdateDomainRequest;
import com.uber.cadence.UpdateDomainResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowExecutionContinuedAsNewEventAttributes;
import com.uber.cadence.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TestWorkflowService implements IWorkflowService {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowService.class);

  private final Lock lock = new ReentrantLock();

  private final TestWorkflowStore store = new TestWorkflowStoreImpl();

  private final Map<ExecutionId, TestWorkflowMutableState> executions = new HashMap<>();

  // key->WorkflowId
  private final Map<WorkflowId, TestWorkflowMutableState> openExecutions = new HashMap<>();

  public void close() {
    store.close();
  }

  private TestWorkflowMutableState getMutableState(ExecutionId executionId)
      throws InternalServiceError, EntityNotExistsError {
    lock.lock();
    try {
      if (executionId.getExecution().getRunId() == null) {
        return getMutableState(executionId.getWorkflowId());
      }
      TestWorkflowMutableState mutableState = executions.get(executionId);
      if (mutableState == null) {
        throw new InternalServiceError("Execution not found in mutable state: " + executionId);
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  private TestWorkflowMutableState getMutableState(WorkflowId workflowId)
      throws EntityNotExistsError {
    lock.lock();
    try {
      TestWorkflowMutableState mutableState = openExecutions.get(workflowId);
      if (mutableState == null) {
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
      StartWorkflowExecutionRequest startRequest)
      throws BadRequestError, InternalServiceError, WorkflowExecutionAlreadyStartedError,
          ServiceBusyError, TException {
    return startWorkflowExecutionImpl(startRequest, Optional.empty());
  }

  StartWorkflowExecutionResponse startWorkflowExecutionImpl(
      StartWorkflowExecutionRequest startRequest, Optional<TestWorkflowMutableState> parent)
      throws BadRequestError, WorkflowExecutionAlreadyStartedError, InternalServiceError {
    String requestWorkflowId = requireNotNull("WorkflowId", startRequest.getWorkflowId());
    String domain = requireNotNull("Domain", startRequest.getDomain());
    WorkflowId workflowId = new WorkflowId(domain, requestWorkflowId);
    TestWorkflowMutableState running;
    lock.lock();
    try {
      running = openExecutions.get(workflowId);
    } finally {
      lock.unlock();
    }
    if (running != null) {
      WorkflowExecutionAlreadyStartedError error = new WorkflowExecutionAlreadyStartedError();
      WorkflowExecution execution = running.getExecutionId().getExecution();
      error.setMessage(
          String.format(
              "Workflow execution already running. WorkflowId: %s, " + "RunId: %s",
              execution.getWorkflowId(), execution.getRunId()));
      error.setRunId(execution.getRunId());
      error.setStartRequestId(startRequest.getRequestId());
      throw error;
    }
    return startWorkflowExecutionNoRunningCheck(startRequest, parent, workflowId);
  }

  private StartWorkflowExecutionResponse startWorkflowExecutionNoRunningCheck(
      StartWorkflowExecutionRequest startRequest,
      Optional<TestWorkflowMutableState> parent,
      WorkflowId workflowId)
      throws InternalServiceError {
    String domain = startRequest.getDomain();
    TestWorkflowMutableState result =
        new TestWorkflowMutableStateImpl(startRequest, parent, this, store);
    WorkflowExecution execution = result.getExecutionId().getExecution();
    ExecutionId executionId = new ExecutionId(domain, execution);
    lock.lock();
    try {
      openExecutions.put(workflowId, result);
      executions.put(executionId, result);
    } finally {
      lock.unlock();
    }
    result.startWorkflow();
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
      return task;
    } catch (EntityNotExistsError e) {
      if (log.isDebugEnabled()) {
        log.debug("Skipping outdated decision task for " + executionId, e);
      }
      // skip the task
    }
    return task;
  }

  @Override
  public void RespondDecisionTaskCompleted(RespondDecisionTaskCompletedRequest request)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    TestWorkflowMutableState mutableState =
        getMutableState(ExecutionId.fromBytes(request.getTaskToken()));
    mutableState.completeDecisionTask(request);
  }

  @Override
  public void RespondDecisionTaskFailed(RespondDecisionTaskFailedRequest failedRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, TException {
    TestWorkflowMutableState mutableState =
        getMutableState(ExecutionId.fromBytes(failedRequest.getTaskToken()));
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
    return mutableState.heartbeatActivityTask(activityId.getId(), heartbeatRequest);
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
      throws BadRequestError, InternalServiceError, EntityNotExistsError,
          CancellationAlreadyRequestedError, ServiceBusyError, TException {
    ExecutionId executionId =
        new ExecutionId(cancelRequest.getDomain(), cancelRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.requestCancelWorkflowExecution(cancelRequest);
  }

  @Override
  public void SignalWorkflowExecution(SignalWorkflowExecutionRequest signalRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    ExecutionId executionId =
        new ExecutionId(signalRequest.getDomain(), signalRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.signal(signalRequest);
  }

  public void signalExternalWorkflowExecution(
      String signalId,
      SignalExternalWorkflowExecutionDecisionAttributes a,
      TestWorkflowMutableState source)
      throws InternalServiceError, EntityNotExistsError {
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
      String identity,
      ExecutionId executionId,
      Optional<TestWorkflowMutableState> parent)
      throws InternalServiceError {
    StartWorkflowExecutionRequest startRequest =
        new StartWorkflowExecutionRequest()
            .setInput(a.getInput())
            .setWorkflowType(a.getWorkflowType())
            .setExecutionStartToCloseTimeoutSeconds(a.getExecutionStartToCloseTimeoutSeconds())
            .setTaskStartToCloseTimeoutSeconds(a.getTaskStartToCloseTimeoutSeconds())
            .setDomain(executionId.getDomain())
            .setTaskList(a.getTaskList())
            .setWorkflowId(executionId.getWorkflowId().getWorkflowId())
            .setWorkflowIdReusePolicy(previousRunStartRequest.getWorkflowIdReusePolicy())
            .setIdentity(identity);
    StartWorkflowExecutionResponse response =
        startWorkflowExecutionNoRunningCheck(startRequest, parent, executionId.getWorkflowId());
    return response.getRunId();
  }

  @Override
  public ListOpenWorkflowExecutionsResponse ListOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ListClosedWorkflowExecutionsResponse ListClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
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
    ForkJoinPool.commonPool()
        .execute(
            () -> {
              try {
                GetWorkflowExecutionHistoryResponse result =
                    GetWorkflowExecutionHistory(getRequest);
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
  public void RespondQueryTaskCompleted(
      RespondQueryTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
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

  public void getDiagnostics(StringBuilder result) {
    store.getDiagnostics(result);
  }

  public long currentTimeMillis() {
    return store.getTimer().getClock().getAsLong();
  }

  public void registerDelayedCallback(Duration delay, Runnable r) {
    store.registerDelayedCallback(delay, r);
  }
}
