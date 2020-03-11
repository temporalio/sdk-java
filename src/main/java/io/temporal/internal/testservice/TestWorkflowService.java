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

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.temporal.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import io.temporal.internal.testservice.TestWorkflowStore.WorkflowState;
import io.temporal.proto.common.RetryPolicy;
import io.temporal.proto.common.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.proto.common.WorkflowExecutionInfo;
import io.temporal.proto.enums.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.proto.enums.WorkflowExecutionCloseStatus;
import io.temporal.proto.enums.WorkflowIdReusePolicy;
import io.temporal.proto.failure.WorkflowExecutionAlreadyStarted;
import io.temporal.proto.workflowservice.CountWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.CountWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.DeprecateDomainRequest;
import io.temporal.proto.workflowservice.DeprecateDomainResponse;
import io.temporal.proto.workflowservice.DescribeDomainRequest;
import io.temporal.proto.workflowservice.DescribeDomainResponse;
import io.temporal.proto.workflowservice.GetSearchAttributesRequest;
import io.temporal.proto.workflowservice.GetSearchAttributesResponse;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryRequest;
import io.temporal.proto.workflowservice.GetWorkflowExecutionHistoryResponse;
import io.temporal.proto.workflowservice.ListArchivedWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListArchivedWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.ListClosedWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListClosedWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.ListDomainsRequest;
import io.temporal.proto.workflowservice.ListDomainsResponse;
import io.temporal.proto.workflowservice.ListOpenWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListOpenWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.ListWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ListWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.PollForActivityTaskRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskResponse;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.QueryWorkflowRequest;
import io.temporal.proto.workflowservice.QueryWorkflowResponse;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatByIDRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatByIDResponse;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatResponse;
import io.temporal.proto.workflowservice.RegisterDomainRequest;
import io.temporal.proto.workflowservice.RegisterDomainResponse;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.ResetStickyTaskListRequest;
import io.temporal.proto.workflowservice.ResetStickyTaskListResponse;
import io.temporal.proto.workflowservice.ResetWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.ResetWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledByIDResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIDResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedByIDResponse;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedResponse;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedResponse;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedResponse;
import io.temporal.proto.workflowservice.RespondQueryTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondQueryTaskCompletedResponse;
import io.temporal.proto.workflowservice.ScanWorkflowExecutionsRequest;
import io.temporal.proto.workflowservice.ScanWorkflowExecutionsResponse;
import io.temporal.proto.workflowservice.SignalWithStartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.SignalWithStartWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.TerminateWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.TerminateWorkflowExecutionResponse;
import io.temporal.proto.workflowservice.UpdateDomainRequest;
import io.temporal.proto.workflowservice.UpdateDomainResponse;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import io.temporal.serviceclient.GrpcStatusUtils;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
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
public final class TestWorkflowService extends WorkflowServiceGrpc.WorkflowServiceImplBase
    implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowService.class);

  private final Lock lock = new ReentrantLock();

  private final TestWorkflowStore store = new TestWorkflowStoreImpl();

  private final Map<ExecutionId, TestWorkflowMutableState> executions = new HashMap<>();

  // key->WorkflowId
  private final Map<WorkflowId, TestWorkflowMutableState> executionsByWorkflowId = new HashMap<>();

  private final ForkJoinPool forkJoinPool = new ForkJoinPool(4);

  private final String serverName;

  public GrpcWorkflowServiceFactory newClientStub() {
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    return new GrpcWorkflowServiceFactory(channel);
  }

  public TestWorkflowService(boolean lockTimeSkipping) {
    this();
    if (lockTimeSkipping) {
      this.lockTimeSkipping("constructor");
    }
  }

  // TODO: Shutdown.
  public TestWorkflowService() {
    serverName = InProcessServerBuilder.generateName();
    try {
      Server server =
          InProcessServerBuilder.forName(serverName)
              .directExecutor()
              .addService(this)
              .build()
              .start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // TODO(maxim): Figure out the shutdown story for Grpc
  //  @Override
  public void close() {
    store.close();
  }

  private TestWorkflowMutableState getMutableState(ExecutionId executionId) {
    return getMutableState(executionId, true);
  }

  private TestWorkflowMutableState getMutableState(ExecutionId executionId, boolean failNotExists) {
    lock.lock();
    try {
      if (executionId.getExecution().getRunId().isEmpty()) {
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
          UUID.randomUUID().toString(),
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
      String runId,
      Optional<String> continuedExecutionRunId,
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
            runId,
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
    forkJoinPool.execute(
        () -> {
          try {
            responseObserver.onNext(
                store.getWorkflowExecutionHistory(mutableState.getExecutionId(), getRequest));
            responseObserver.onCompleted();
          } catch (Exception e) {
            responseObserver.onError(e);
          }
        });
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
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
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
    DecisionTaskToken taskToken = DecisionTaskToken.fromBytes(request.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
    mutableState.completeDecisionTask(taskToken.getHistorySize(), request);
    responseObserver.onNext(RespondDecisionTaskCompletedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondDecisionTaskFailed(
      RespondDecisionTaskFailedRequest failedRequest,
      StreamObserver<RespondDecisionTaskFailedResponse> responseObserver) {
    DecisionTaskToken taskToken = DecisionTaskToken.fromBytes(failedRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
    mutableState.failDecisionTask(failedRequest);
    responseObserver.onNext(RespondDecisionTaskFailedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void pollForActivityTask(
      PollForActivityTaskRequest pollRequest,
      StreamObserver<PollForActivityTaskResponse> responseObserver) {
    PollForActivityTaskResponse.Builder task;
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
        responseObserver.onNext(task.build());
        responseObserver.onCompleted();
        return;
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
          if (log.isDebugEnabled()) {
            log.debug("Skipping outdated activity task for " + executionId, e);
          }
        } else {
          responseObserver.onError(e);
          responseObserver.onCompleted();
          return;
        }
      }
    }
  }

  @Override
  public void recordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest,
      StreamObserver<RecordActivityTaskHeartbeatResponse> responseObserver) {
    ActivityId activityId = ActivityId.fromBytes(heartbeatRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    boolean cancelRequested =
        mutableState.heartbeatActivityTask(activityId.getId(), heartbeatRequest.getDetails());
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
            heartbeatRequest.getActivityID(), heartbeatRequest.getDetails());
    responseObserver.onNext(
        RecordActivityTaskHeartbeatByIDResponse.newBuilder()
            .setCancelRequested(cancelRequested)
            .build());
  }

  @Override
  public void respondActivityTaskCompleted(
      RespondActivityTaskCompletedRequest completeRequest,
      StreamObserver<RespondActivityTaskCompletedResponse> responseObserver) {
    ActivityId activityId = ActivityId.fromBytes(completeRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    mutableState.completeActivityTask(activityId.getId(), completeRequest);
    responseObserver.onNext(RespondActivityTaskCompletedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondActivityTaskCompletedByID(
      RespondActivityTaskCompletedByIDRequest completeRequest,
      StreamObserver<RespondActivityTaskCompletedByIDResponse> responseObserver) {
    ActivityId activityId =
        new ActivityId(
            completeRequest.getDomain(),
            completeRequest.getWorkflowID(),
            completeRequest.getRunID(),
            completeRequest.getActivityID());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
    mutableState.completeActivityTaskById(activityId.getId(), completeRequest);
    responseObserver.onNext(RespondActivityTaskCompletedByIDResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondActivityTaskFailed(
      RespondActivityTaskFailedRequest failRequest,
      StreamObserver<RespondActivityTaskFailedResponse> responseObserver) {
    ActivityId activityId = ActivityId.fromBytes(failRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    mutableState.failActivityTask(activityId.getId(), failRequest);
    responseObserver.onNext(RespondActivityTaskFailedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondActivityTaskFailedByID(
      RespondActivityTaskFailedByIDRequest failRequest,
      StreamObserver<RespondActivityTaskFailedByIDResponse> responseObserver) {
    ActivityId activityId =
        new ActivityId(
            failRequest.getDomain(),
            failRequest.getWorkflowID(),
            failRequest.getRunID(),
            failRequest.getActivityID());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
    mutableState.failActivityTaskById(activityId.getId(), failRequest);
    responseObserver.onNext(RespondActivityTaskFailedByIDResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondActivityTaskCanceled(
      RespondActivityTaskCanceledRequest canceledRequest,
      StreamObserver<RespondActivityTaskCanceledResponse> responseObserver) {
    ActivityId activityId = ActivityId.fromBytes(canceledRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getExecutionId());
    mutableState.cancelActivityTask(activityId.getId(), canceledRequest);
    responseObserver.onNext(RespondActivityTaskCanceledResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void respondActivityTaskCanceledByID(
      RespondActivityTaskCanceledByIDRequest canceledRequest,
      StreamObserver<RespondActivityTaskCanceledByIDResponse> responseObserver) {
    ActivityId activityId =
        new ActivityId(
            canceledRequest.getDomain(),
            canceledRequest.getWorkflowID(),
            canceledRequest.getRunID(),
            canceledRequest.getActivityID());
    TestWorkflowMutableState mutableState = getMutableState(activityId.getWorkflowId());
    mutableState.cancelActivityTaskById(activityId.getId(), canceledRequest);
    responseObserver.onNext(RespondActivityTaskCanceledByIDResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      StreamObserver<RequestCancelWorkflowExecutionResponse> responseObserver) {
    requestCancelWorkflowExecution(cancelRequest);
    responseObserver.onNext(RequestCancelWorkflowExecutionResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest) {
    ExecutionId executionId =
        new ExecutionId(cancelRequest.getDomain(), cancelRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.requestCancelWorkflowExecution(cancelRequest);
  }

  @Override
  public void signalWorkflowExecution(
      SignalWorkflowExecutionRequest signalRequest,
      StreamObserver<SignalWorkflowExecutionResponse> responseObserver) {
    ExecutionId executionId =
        new ExecutionId(signalRequest.getDomain(), signalRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.signal(signalRequest);
    responseObserver.onNext(SignalWorkflowExecutionResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest r,
      StreamObserver<SignalWithStartWorkflowExecutionResponse> responseObserver) {
    ExecutionId executionId = new ExecutionId(r.getDomain(), r.getWorkflowId(), null);
    TestWorkflowMutableState mutableState = getMutableState(executionId, false);
    SignalWorkflowExecutionRequest signalRequest =
        SignalWorkflowExecutionRequest.newBuilder()
            .setInput(r.getSignalInput())
            .setSignalName(r.getSignalName())
            .setControl(r.getControl())
            .setDomain(r.getDomain())
            .setWorkflowExecution(executionId.getExecution())
            .setRequestId(r.getRequestId())
            .setIdentity(r.getIdentity())
            .build();
    if (mutableState != null) {
      mutableState.signal(signalRequest);
      responseObserver.onNext(
          SignalWithStartWorkflowExecutionResponse.newBuilder()
              .setRunId(mutableState.getExecutionId().getExecution().getRunId())
              .build());
      responseObserver.onCompleted();
      return;
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
    StartWorkflowExecutionResponse startResult =
        startWorkflowExecutionImpl(
            startRequest, 0, Optional.empty(), OptionalLong.empty(), Optional.of(signalRequest));
    responseObserver.onNext(
        SignalWithStartWorkflowExecutionResponse.newBuilder()
            .setRunId(startResult.getRunId())
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void resetWorkflowExecution(
      ResetWorkflowExecutionRequest request,
      StreamObserver<ResetWorkflowExecutionResponse> responseObserver) {
    super.resetWorkflowExecution(request, responseObserver);
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
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        source.failSignalExternalWorkflowExecution(
            signalId,
            SignalExternalWorkflowExecutionFailedCause
                .SignalExternalWorkflowExecutionFailedCauseUnknownExternalWorkflowExecution);
      } else {
        throw e;
      }
    }
  }

  @Override
  public void terminateWorkflowExecution(
      TerminateWorkflowExecutionRequest request,
      StreamObserver<TerminateWorkflowExecutionResponse> responseObserver) {
    super.terminateWorkflowExecution(request, responseObserver);
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
    StartWorkflowExecutionRequest.Builder startRequestBuilder =
        StartWorkflowExecutionRequest.newBuilder()
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
    if (!a.getInput().isEmpty()) {
      startRequestBuilder.setInput(a.getInput());
    }
    StartWorkflowExecutionRequest startRequest = startRequestBuilder.build();
    lock.lock();
    try {
      StartWorkflowExecutionResponse response =
          startWorkflowExecutionNoRunningCheckLocked(
              startRequest,
              a.getNewExecutionRunId(),
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
  public void listOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest,
      StreamObserver<ListOpenWorkflowExecutionsResponse> responseObserver) {
    Optional<String> workflowIdFilter;
    if (listRequest.hasExecutionFilter()
        && !listRequest.getExecutionFilter().getWorkflowId().isEmpty()) {
      workflowIdFilter = Optional.of(listRequest.getExecutionFilter().getWorkflowId());
    } else {
      workflowIdFilter = Optional.empty();
    }
    List<WorkflowExecutionInfo> result = store.listWorkflows(WorkflowState.OPEN, workflowIdFilter);
    responseObserver.onNext(
        ListOpenWorkflowExecutionsResponse.newBuilder().addAllExecutions(result).build());
    responseObserver.onCompleted();
  }

  @Override
  public void listClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest,
      StreamObserver<ListClosedWorkflowExecutionsResponse> responseObserver) {
    Optional<String> workflowIdFilter;
    if (listRequest.hasExecutionFilter()
        && !listRequest.getExecutionFilter().getWorkflowId().isEmpty()) {
      workflowIdFilter = Optional.of(listRequest.getExecutionFilter().getWorkflowId());
    } else {
      workflowIdFilter = Optional.empty();
    }
    List<WorkflowExecutionInfo> result =
        store.listWorkflows(WorkflowState.CLOSED, workflowIdFilter);
    responseObserver.onNext(
        ListClosedWorkflowExecutionsResponse.newBuilder().addAllExecutions(result).build());
    responseObserver.onCompleted();
  }

  @Override
  public void listWorkflowExecutions(
      ListWorkflowExecutionsRequest request,
      StreamObserver<ListWorkflowExecutionsResponse> responseObserver) {
    super.listWorkflowExecutions(request, responseObserver);
  }

  @Override
  public void listArchivedWorkflowExecutions(
      ListArchivedWorkflowExecutionsRequest request,
      StreamObserver<ListArchivedWorkflowExecutionsResponse> responseObserver) {
    super.listArchivedWorkflowExecutions(request, responseObserver);
  }

  @Override
  public void scanWorkflowExecutions(
      ScanWorkflowExecutionsRequest request,
      StreamObserver<ScanWorkflowExecutionsResponse> responseObserver) {
    super.scanWorkflowExecutions(request, responseObserver);
  }

  @Override
  public void countWorkflowExecutions(
      CountWorkflowExecutionsRequest request,
      StreamObserver<CountWorkflowExecutionsResponse> responseObserver) {
    super.countWorkflowExecutions(request, responseObserver);
  }

  @Override
  public void getSearchAttributes(
      GetSearchAttributesRequest request,
      StreamObserver<GetSearchAttributesResponse> responseObserver) {
    super.getSearchAttributes(request, responseObserver);
  }

  @Override
  public void respondQueryTaskCompleted(
      RespondQueryTaskCompletedRequest completeRequest,
      StreamObserver<RespondQueryTaskCompletedResponse> responseObserver) {
    QueryId queryId = QueryId.fromBytes(completeRequest.getTaskToken());
    TestWorkflowMutableState mutableState = getMutableState(queryId.getExecutionId());
    mutableState.completeQuery(queryId, completeRequest);
    responseObserver.onNext(RespondQueryTaskCompletedResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void resetStickyTaskList(
      ResetStickyTaskListRequest request,
      StreamObserver<ResetStickyTaskListResponse> responseObserver) {
    super.resetStickyTaskList(request, responseObserver);
  }

  @Override
  public void queryWorkflow(
      QueryWorkflowRequest queryRequest, StreamObserver<QueryWorkflowResponse> responseObserver) {
    ExecutionId executionId =
        new ExecutionId(queryRequest.getDomain(), queryRequest.getExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    QueryWorkflowResponse result = mutableState.query(queryRequest);
    responseObserver.onNext(result);
    responseObserver.onCompleted();
  }

  private <R> R requireNotNull(String fieldName, R value) {
    if (value == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Missing requried field \"" + fieldName + "\".")
          .asRuntimeException();
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
