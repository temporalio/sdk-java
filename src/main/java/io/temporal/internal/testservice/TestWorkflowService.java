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

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.temporal.*;
import io.temporal.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import io.temporal.internal.testservice.TestWorkflowStore.WorkflowState;
import io.temporal.serviceclient.GrpcFailure;
import io.temporal.serviceclient.GrpcStatusUtils;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory implementation of the Temporal service. To be used for testing purposes only. Do not
 * use directly. Instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
public final class TestWorkflowService extends GrpcWorkflowServiceFactory {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowService.class);
  private final Lock lock = new ReentrantLock();
  private final TestWorkflowStore store = new TestWorkflowStoreImpl();
  private final Map<ExecutionId, TestWorkflowMutableState> executions = new HashMap<>();
  // key->WorkflowId
  private final Map<WorkflowId, TestWorkflowMutableState> executionsByWorkflowId = new HashMap<>();
  private final ForkJoinPool forkJoinPool = new ForkJoinPool(4);
  private Server server;
  private MockWorkflowService mockService;

  public TestWorkflowService() {
    String serverName = InProcessServerBuilder.generateName();
    // Initialize an in-memory mock service.
    try {
      server =
          InProcessServerBuilder.forName(serverName)
              .directExecutor()
              .addService(getMockService())
              .build()
              .start();
    } catch (IOException e) {
      // This should not happen with in-memory services, but rethrow just in case.
      throw new RuntimeException(e);
    }
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    blockingStub = WorkflowServiceGrpc.newBlockingStub(channel);
  }

  @Override
  public void close() {
    store.close();
    server.shutdownNow();
  }

  public MockWorkflowService getMockService() {
    if (mockService == null) {
      mockService = new MockWorkflowService();
    }
    return mockService;
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
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription(
                "Execution not found in mutable state: " + executionId));
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
        throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription(
                "Execution not found in mutable state: " + workflowId));
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Mock in-memory service for testing purposes. Didn't use Mockito here because a lot of
   * overloading is required and Mockito would make the code less readable.
   */
  class MockWorkflowService extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    @Override
    public void startWorkflowExecution(
        StartWorkflowExecutionRequest startRequest,
        StreamObserver<StartWorkflowExecutionResponse> responseObserver) {
      responseObserver.onNext(
          startWorkflowExecutionImpl(
              startRequest, 0, Optional.empty(), OptionalLong.empty(), Optional.empty()));
      responseObserver.onCompleted();
    }

    public StartWorkflowExecutionResponse startWorkflowExecutionImpl(
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

    private Optional<RetryState> newRetryStateLocked(RetryPolicy retryPolicy) {
      // RetryPolicy often comes from GRPC messages which don't support null, so we need to compare
      // with default state.
      if (retryPolicy == null || RetryPolicy.getDefaultInstance().equals(retryPolicy)) {
        return Optional.empty();
      }
      long expirationInterval =
          TimeUnit.SECONDS.toMillis(retryPolicy.getExpirationIntervalInSeconds());
      long expirationTime = store.currentTimeMillis() + expirationInterval;
      return Optional.of(new RetryState(retryPolicy, expirationTime));
    }

    private StartWorkflowExecutionResponse throwDuplicatedWorkflow(
        StartWorkflowExecutionRequest startRequest, TestWorkflowMutableState existing) {
      // WorkflowExecutionAlreadyStartedError error = new WorkflowExecutionAlreadyStartedError();
      WorkflowExecution execution = existing.getExecutionId().getExecution();
      StatusRuntimeException e =
          new StatusRuntimeException(
              Status.ALREADY_EXISTS.withDescription(
                  String.format(
                      "WorkflowId: %s, RunId: %s",
                      execution.getWorkflowId(), execution.getRunId())));
      GrpcStatusUtils.setFailure(
          e,
          GrpcFailure.WORKFLOW_EXECUTION_ALREADY_STARTED_FAILURE,
          WorkflowExecutionAlreadyStartedFailure.newBuilder()
              .setRunId(execution.getRunId())
              .setStartRequestId(startRequest.getRequestId())
              .build());
      throw e;
    }

    private StartWorkflowExecutionResponse startWorkflowExecutionNoRunningCheckLocked(
        StartWorkflowExecutionRequest startRequest,
        boolean continuedAsNew,
        Optional<RetryState> retryState,
        int backoffStartIntervalInSeconds,
        ByteString lastCompletionResult,
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
              TestWorkflowService.this,
              store);
      WorkflowExecution execution = mutableState.getExecutionId().getExecution();
      ExecutionId executionId = new ExecutionId(domain, execution);
      executionsByWorkflowId.put(workflowId, mutableState);
      executions.put(executionId, mutableState);
      mutableState.startWorkflow(continuedAsNew, signalWithStartSignal);
      return StartWorkflowExecutionResponse.newBuilder().setRunId(execution.getRunId()).build();
    }

    @Override
    public void getWorkflowExecutionHistory(
        GetWorkflowExecutionHistoryRequest getRequest,
        StreamObserver<GetWorkflowExecutionHistoryResponse> streamObserver) {
      ExecutionId executionId = new ExecutionId(getRequest.getDomain(), getRequest.getExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);

      try {
        GetWorkflowExecutionHistoryResponse resp =
            store.getWorkflowExecutionHistory(mutableState.getExecutionId(), getRequest);
        streamObserver.onNext(resp);
        streamObserver.onCompleted();
      } catch (Exception e) {
        // Exceptions thrown inside the service by default propagate as meaningless UNKNOWN
        // exceptions. In order to propagate a meaningful exception we need to catch it here and use
        // onError().
        streamObserver.onError(e);
      }
    }

    @Override
    public void pollForDecisionTask(
        PollForDecisionTaskRequest pollRequest,
        StreamObserver<PollForDecisionTaskResponse> streamObserver) {
      PollForDecisionTaskResponse task = PollForDecisionTaskResponse.getDefaultInstance();
      try {
        task = store.pollForDecisionTask(pollRequest);
      } catch (InterruptedException e) {
        streamObserver.onNext(PollForDecisionTaskResponse.getDefaultInstance());
        streamObserver.onCompleted();
      }
      ExecutionId executionId =
          new ExecutionId(pollRequest.getDomain(), task.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      try {
        mutableState.startDecisionTask(task, pollRequest);
        // The task always has the original tasklist is was created on as part of the response. This
        // may different
        // then the task list it was scheduled on as in the case of sticky execution.
        task.toBuilder()
            .setWorkflowExecutionTaskList(mutableState.getStartRequest().getTaskList())
            .build();
        streamObserver.onNext(task);
        streamObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND) && log.isDebugEnabled()) {
          log.debug("Skipping outdated decision task for " + executionId, e);
        }
        // skip the task
      }
      task.toBuilder()
          .setWorkflowExecutionTaskList(mutableState.getStartRequest().getTaskList())
          .build();
      streamObserver.onNext(task);
      streamObserver.onCompleted();
    }

    @Override
    public void respondDecisionTaskCompleted(
        RespondDecisionTaskCompletedRequest request,
        StreamObserver<RespondDecisionTaskCompletedResponse> streamObserver) {
      DecisionTaskToken taskToken =
          DecisionTaskToken.fromBytes(request.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
      mutableState.completeDecisionTask(taskToken.getHistorySize(), request);
      streamObserver.onNext(RespondDecisionTaskCompletedResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void respondDecisionTaskFailed(
        RespondDecisionTaskFailedRequest failedRequest,
        StreamObserver<RespondDecisionTaskFailedResponse> streamObserver) {
      DecisionTaskToken taskToken =
          DecisionTaskToken.fromBytes(failedRequest.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
      mutableState.failDecisionTask(failedRequest);
      streamObserver.onNext(RespondDecisionTaskFailedResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void pollForActivityTask(
        PollForActivityTaskRequest pollRequest,
        StreamObserver<PollForActivityTaskResponse> streamObserver) {
      PollForActivityTaskResponse task = PollForActivityTaskResponse.getDefaultInstance();
      while (true) {
        try {
          task = store.pollForActivityTask(pollRequest);
        } catch (InterruptedException e) {
          streamObserver.onNext(PollForActivityTaskResponse.getDefaultInstance());
          streamObserver.onCompleted();
        }
        ExecutionId executionId =
            new ExecutionId(pollRequest.getDomain(), task.getWorkflowExecution());
        TestWorkflowMutableState mutableState = getMutableState(executionId);
        try {
          mutableState.startActivityTask(task, pollRequest);
          streamObserver.onNext(task);
          streamObserver.onCompleted();
        } catch (StatusRuntimeException e) {
          if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND) && log.isDebugEnabled()) {
            log.debug("Skipping outdated activity task for " + executionId, e);
          }
        }
      }
    }

    @Override
    public void recordActivityTaskHeartbeat(
        RecordActivityTaskHeartbeatRequest heartbeatRequest,
        StreamObserver<RecordActivityTaskHeartbeatResponse> streamObserver) {
      ActivityId activityId = ActivityId.fromBytes(heartbeatRequest.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
      streamObserver.onNext(
          mutableState.heartbeatActivityTask(
              activityId.getId(), heartbeatRequest.getDetails().toByteArray()));
      streamObserver.onCompleted();
    }

    @Override
    public void recordActivityTaskHeartbeatByID(
        RecordActivityTaskHeartbeatByIDRequest heartbeatRequest,
        StreamObserver<RecordActivityTaskHeartbeatByIDResponse> streamObserver) {
      ExecutionId execution =
          new ExecutionId(
              heartbeatRequest.getDomain(),
              heartbeatRequest.getWorkflowID(),
              heartbeatRequest.getRunID());
      TestWorkflowMutableState mutableState = getMutableState(execution);
      streamObserver.onNext(
          RecordActivityTaskHeartbeatByIDResponse.newBuilder()
              .setCancelRequested(
                  mutableState
                      .heartbeatActivityTask(
                          heartbeatRequest.getActivityID(),
                          heartbeatRequest.getDetails().toByteArray())
                      .getCancelRequested())
              .build());
      streamObserver.onCompleted();
    }

    @Override
    public void respondActivityTaskCompleted(
        RespondActivityTaskCompletedRequest completeRequest,
        StreamObserver<RespondActivityTaskCompletedResponse> streamObserver) {
      ActivityId activityId = ActivityId.fromBytes(completeRequest.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
      mutableState.completeActivityTask(activityId.getId(), completeRequest);
      streamObserver.onNext(RespondActivityTaskCompletedResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void respondActivityTaskCompletedByID(
        RespondActivityTaskCompletedByIDRequest completeRequest,
        StreamObserver<RespondActivityTaskCompletedByIDResponse> streamObserver) {
      ActivityId activityId =
          new ActivityId(
              completeRequest.getDomain(),
              completeRequest.getWorkflowID(),
              completeRequest.getRunID(),
              completeRequest.getActivityID());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
      mutableState.completeActivityTaskById(activityId.getId(), completeRequest);
      streamObserver.onNext(RespondActivityTaskCompletedByIDResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void respondActivityTaskFailed(
        RespondActivityTaskFailedRequest failRequest,
        StreamObserver<RespondActivityTaskFailedResponse> streamObserver) {
      ActivityId activityId = ActivityId.fromBytes(failRequest.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
      mutableState.failActivityTask(activityId.getId(), failRequest);
      streamObserver.onNext(RespondActivityTaskFailedResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void respondActivityTaskFailedByID(
        RespondActivityTaskFailedByIDRequest failRequest,
        StreamObserver<RespondActivityTaskFailedByIDResponse> streamObserver) {
      ActivityId activityId =
          new ActivityId(
              failRequest.getDomain(),
              failRequest.getWorkflowID(),
              failRequest.getRunID(),
              failRequest.getActivityID());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
      mutableState.failActivityTaskById(activityId.getId(), failRequest);
      streamObserver.onNext(RespondActivityTaskFailedByIDResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void respondActivityTaskCanceled(
        RespondActivityTaskCanceledRequest canceledRequest,
        StreamObserver<RespondActivityTaskCanceledResponse> streamObserver) {
      ActivityId activityId = ActivityId.fromBytes(canceledRequest.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
      mutableState.cancelActivityTask(activityId.getId(), canceledRequest);
      streamObserver.onNext(RespondActivityTaskCanceledResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void respondActivityTaskCanceledByID(
        RespondActivityTaskCanceledByIDRequest canceledRequest,
        StreamObserver<RespondActivityTaskCanceledByIDResponse> streamObserver) {
      ActivityId activityId =
          new ActivityId(
              canceledRequest.getDomain(),
              canceledRequest.getWorkflowID(),
              canceledRequest.getRunID(),
              canceledRequest.getActivityID());
      TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
      mutableState.cancelActivityTaskById(activityId.getId(), canceledRequest);
      streamObserver.onNext(RespondActivityTaskCanceledByIDResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void requestCancelWorkflowExecution(
        RequestCancelWorkflowExecutionRequest cancelRequest,
        StreamObserver<RequestCancelWorkflowExecutionResponse> streamObserver) {
      ExecutionId executionId =
          new ExecutionId(cancelRequest.getDomain(), cancelRequest.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      mutableState.requestCancelWorkflowExecution(cancelRequest);
      if (streamObserver != null) {
        streamObserver.onNext(RequestCancelWorkflowExecutionResponse.getDefaultInstance());
        streamObserver.onCompleted();
      }
    }

    @Override
    public void signalWorkflowExecution(
        SignalWorkflowExecutionRequest signalRequest,
        StreamObserver<SignalWorkflowExecutionResponse> streamObserver) {
      ExecutionId executionId =
          new ExecutionId(signalRequest.getDomain(), signalRequest.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      mutableState.signal(signalRequest);
      streamObserver.onNext(SignalWorkflowExecutionResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void signalWithStartWorkflowExecution(
        SignalWithStartWorkflowExecutionRequest r,
        StreamObserver<SignalWithStartWorkflowExecutionResponse> streamObserver) {
      ExecutionId executionId = new ExecutionId(r.getDomain(), r.getWorkflowId(), null);
      TestWorkflowMutableState mutableState = getMutableState(executionId, false);
      SignalWorkflowExecutionRequest signalRequest =
          SignalWorkflowExecutionRequest.newBuilder()
              .setControl(r.getControl())
              .setDomain(r.getDomain())
              .setIdentity(r.getIdentity())
              .setInput(r.getSignalInput())
              .setRequestId(r.getRequestId())
              .setSignalName(r.getSignalName())
              .setWorkflowExecution(executionId.getExecution())
              .build();
      if (mutableState != null) {
        mutableState.signal(signalRequest);
        SignalWithStartWorkflowExecutionResponse rsp =
            SignalWithStartWorkflowExecutionResponse.newBuilder()
                .setRunId(mutableState.getExecutionId().getExecution().getRunId())
                .build();
        streamObserver.onNext(rsp);
        streamObserver.onCompleted();
      }
      StartWorkflowExecutionRequest startRequest =
          StartWorkflowExecutionRequest.newBuilder()
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
              .setIdentity(r.getIdentity())
              .build();
      streamObserver.onNext(
          SignalWithStartWorkflowExecutionResponse.newBuilder()
              .setRunId(
                  startWorkflowExecutionImpl(
                          startRequest,
                          0,
                          Optional.empty(),
                          OptionalLong.empty(),
                          Optional.of(signalRequest))
                      .getRunId())
              .build());
      streamObserver.onCompleted();
    }

    // TODO: https://github.io.temporal-java-client/issues/359
    @Override
    public void resetWorkflowExecution(
        ResetWorkflowExecutionRequest resetRequest,
        StreamObserver<ResetWorkflowExecutionResponse> streamObserver) {
      streamObserver.onNext(null);
      streamObserver.onCompleted();
    }

    public void signalExternalWorkflowExecution(
        String signalId,
        SignalExternalWorkflowExecutionDecisionAttributes a,
        TestWorkflowMutableState source) {
      ExecutionId executionId = new ExecutionId(a.getDomain(), a.getExecution());
      TestWorkflowMutableState mutableState = null;
      try {
        mutableState = getMutableState(executionId);
        mutableState.signalFromWorkflow(a);
        source.completeSignalExternalWorkflowExecution(
            signalId, mutableState.getExecutionId().getExecution().getRunId());
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.NOT_FOUND)) {
          source.failSignalExternalWorkflowExecution(
              signalId,
              SignalExternalWorkflowExecutionFailedCause
                  .SignalExternalWorkflowExecutionFailedCauseUnknownExternalWorkflowExecution);
        }
      }
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
        OptionalLong parentChildInitiatedEventId) {
      StartWorkflowExecutionRequest startRequest =
          StartWorkflowExecutionRequest.newBuilder()
              .setRequestId(UUID.randomUUID().toString())
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
              .build();
      // TODO: (vkoby) Revisit
      if (a.getInput() != null) {
        startRequest.toBuilder().setInput(a.getInput()).build();
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
    public void listOpenWorkflowExecutions(
        ListOpenWorkflowExecutionsRequest listRequest,
        StreamObserver<ListOpenWorkflowExecutionsResponse> streamObserver) {
      Optional<String> workflowIdFilter;
      WorkflowExecutionFilter executionFilter = listRequest.getExecutionFilter();
      if (executionFilter != null
          && Strings.isNullOrEmpty(executionFilter.getWorkflowId())
          && !executionFilter.getWorkflowId().isEmpty()) {
        workflowIdFilter = Optional.of(executionFilter.getWorkflowId());
      } else {
        workflowIdFilter = Optional.empty();
      }
      List<WorkflowExecutionInfo> result =
          store.listWorkflows(WorkflowState.OPEN, workflowIdFilter);
      streamObserver.onNext(
          ListOpenWorkflowExecutionsResponse.newBuilder().addAllExecutions(result).build());
      streamObserver.onCompleted();
    }

    @Override
    public void listClosedWorkflowExecutions(
        ListClosedWorkflowExecutionsRequest listRequest,
        StreamObserver<ListClosedWorkflowExecutionsResponse> streamObserver) {
      Optional<String> workflowIdFilter;
      WorkflowExecutionFilter executionFilter = listRequest.getExecutionFilter();
      if (executionFilter != null
          && Strings.isNullOrEmpty(executionFilter.getWorkflowId())
          && !executionFilter.getWorkflowId().isEmpty()) {
        workflowIdFilter = Optional.of(executionFilter.getWorkflowId());
      } else {
        workflowIdFilter = Optional.empty();
      }
      List<WorkflowExecutionInfo> result =
          store.listWorkflows(WorkflowState.CLOSED, workflowIdFilter);
      streamObserver.onNext(
          ListClosedWorkflowExecutionsResponse.newBuilder().addAllExecutions(result).build());
      streamObserver.onCompleted();
    }

    @Override
    public void respondQueryTaskCompleted(
        RespondQueryTaskCompletedRequest completeRequest,
        StreamObserver<RespondQueryTaskCompletedResponse> streamObserver) {
      QueryId queryId = QueryId.fromBytes(completeRequest.getTaskToken().toByteArray());
      TestWorkflowMutableState mutableState = getMutableState(queryId.getExecutionId());
      mutableState.completeQuery(queryId, completeRequest);
      streamObserver.onNext(RespondQueryTaskCompletedResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }

    @Override
    public void queryWorkflow(
        QueryWorkflowRequest queryRequest, StreamObserver<QueryWorkflowResponse> streamObserver) {
      ExecutionId executionId =
          new ExecutionId(queryRequest.getDomain(), queryRequest.getExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      streamObserver.onNext(mutableState.query(queryRequest));
      streamObserver.onCompleted();
    }
  }

  private <R> R requireNotNull(String fieldName, R value) {
    if (value == null) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription("Missing requried field \"" + fieldName + "\"."));
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
