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

package io.temporal.internal.testservice;

import static io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED;
import static io.temporal.api.workflowservice.v1.ExecuteMultiOperationRequest.Operation.OperationCase.START_WORKFLOW;
import static io.temporal.api.workflowservice.v1.ExecuteMultiOperationRequest.Operation.OperationCase.UPDATE_WORKFLOW;
import static io.temporal.internal.testservice.CronUtils.getBackoffInterval;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.errordetails.v1.MultiOperationExecutionFailure;
import io.temporal.api.errordetails.v1.WorkflowExecutionAlreadyStartedFailure;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.MultiOperationExecutionAborted;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.Request;
import io.temporal.api.nexus.v1.StartOperationResponse;
import io.temporal.api.nexus.v1.UnsuccessfulOperationError;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.SleepRequest;
import io.temporal.api.testservice.v1.TestServiceGrpc;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.common.ProtoUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.testservice.TestWorkflowStore.WorkflowState;
import io.temporal.serviceclient.StatusUtils;
import io.temporal.serviceclient.TestServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testserver.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory implementation of the Workflow Service. To be used for testing purposes only.
 *
 * <p>Do not use directly, instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
public final class TestWorkflowService extends WorkflowServiceGrpc.WorkflowServiceImplBase
    implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(TestWorkflowService.class);
  private final Map<ExecutionId, TestWorkflowMutableState> executions = new HashMap<>();
  // key->WorkflowId
  private final Map<WorkflowId, TestWorkflowMutableState> executionsByWorkflowId = new HashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Lock lock = new ReentrantLock();

  private final TestWorkflowStore store;
  private final TestVisibilityStore visibilityStore;
  private final TestNexusEndpointStore nexusEndpointStore;
  private final SelfAdvancingTimer selfAdvancingTimer;

  private final ScheduledExecutorService backgroundScheduler =
      Executors.newSingleThreadScheduledExecutor();

  private final Server outOfProcessServer;
  private final InProcessGRPCServer inProcessServer;
  private final WorkflowServiceStubs workflowServiceStubs;

  TestWorkflowService(
      TestWorkflowStore store,
      TestVisibilityStore visibilityStore,
      TestNexusEndpointStore nexusEndpointStore,
      SelfAdvancingTimer selfAdvancingTimer) {
    this.store = store;
    this.visibilityStore = visibilityStore;
    this.nexusEndpointStore = nexusEndpointStore;
    this.selfAdvancingTimer = selfAdvancingTimer;
    this.outOfProcessServer = null;
    this.inProcessServer = null;
    this.workflowServiceStubs = null;
  }

  @Override
  public void close() {
    log.debug("Shutting down TestWorkflowService");

    log.debug("Shutting down background scheduler");
    backgroundScheduler.shutdown();

    if (outOfProcessServer != null) {
      log.info("Shutting down out-of-process GRPC server");
      outOfProcessServer.shutdown();
    }

    if (workflowServiceStubs != null) {
      workflowServiceStubs.shutdown();
    }

    if (inProcessServer != null) {
      log.info("Shutting down in-process GRPC server");
      inProcessServer.shutdown();
    }

    executor.shutdown();

    try {
      executor.awaitTermination(1, TimeUnit.SECONDS);

      if (outOfProcessServer != null) {
        outOfProcessServer.awaitTermination(1, TimeUnit.SECONDS);
      }

      if (workflowServiceStubs != null) {
        workflowServiceStubs.awaitTermination(1, TimeUnit.SECONDS);
      }

      if (inProcessServer != null) {
        inProcessServer.awaitTermination(1, TimeUnit.SECONDS);
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("shutdown interrupted", e);
    }

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
        throw Status.NOT_FOUND
            .withDescription(
                "Execution \""
                    + executionId
                    + "\" not found in mutable state. Known executions: "
                    + executions.values()
                    + ", service="
                    + this)
            .asRuntimeException();
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  private TestWorkflowMutableState getMutableState(WorkflowId workflowId, boolean failNotExists) {
    lock.lock();
    try {
      TestWorkflowMutableState mutableState = executionsByWorkflowId.get(workflowId);
      if (mutableState == null && failNotExists) {
        throw Status.NOT_FOUND
            .withDescription("Execution not found in mutable state: " + workflowId)
            .asRuntimeException();
      }
      return mutableState;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void startWorkflowExecution(
      StartWorkflowExecutionRequest request,
      StreamObserver<StartWorkflowExecutionResponse> responseObserver) {
    try {
      Duration backoffInterval = getBackoffInterval(request.getCronSchedule(), store.currentTime());
      StartWorkflowExecutionResponse response =
          startWorkflowExecutionImpl(
              request, backoffInterval, Optional.empty(), OptionalLong.empty(), null);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  StartWorkflowExecutionResponse startWorkflowExecutionImpl(
      StartWorkflowExecutionRequest startRequest,
      Duration backoffStartInterval,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      @Nullable Consumer<TestWorkflowMutableState> withStart) {
    String requestWorkflowId = requireNotNull("WorkflowId", startRequest.getWorkflowId());
    String namespace = requireNotNull("Namespace", startRequest.getNamespace());
    WorkflowId workflowId = new WorkflowId(namespace, requestWorkflowId);
    WorkflowIdReusePolicy reusePolicy = startRequest.getWorkflowIdReusePolicy();
    WorkflowIdConflictPolicy conflictPolicy = startRequest.getWorkflowIdConflictPolicy();
    if (conflictPolicy != WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_UNSPECIFIED
        && reusePolicy == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING) {
      throw createInvalidArgument(
          "Invalid WorkflowIDReusePolicy: WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING cannot be used together with a WorkflowIDConflictPolicy.");
    }

    TestWorkflowMutableState existing;
    lock.lock();
    try {
      String newRunId = UUID.randomUUID().toString();
      existing = executionsByWorkflowId.get(workflowId);
      if (existing != null) {
        WorkflowExecutionStatus status = existing.getWorkflowExecutionStatus();

        if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
            && (reusePolicy == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING
                || conflictPolicy
                    == WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING)) {
          existing.terminateWorkflowExecution(
              TerminateWorkflowExecutionRequest.newBuilder()
                  .setNamespace(startRequest.getNamespace())
                  .setWorkflowExecution(existing.getExecutionId().getExecution())
                  .setReason("TerminateIfRunning WorkflowIdReusePolicy Policy")
                  .setIdentity("history-service")
                  .setDetails(
                      Payloads.newBuilder()
                          .addPayloads(
                              Payload.newBuilder()
                                  .setData(
                                      ByteString.copyFromUtf8(
                                          String.format("terminated by new runID: %s", newRunId)))
                                  .build())
                          .build())
                  .build());
        } else if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
            && conflictPolicy
                == WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING) {
          return StartWorkflowExecutionResponse.newBuilder()
              .setStarted(false)
              .setRunId(existing.getExecutionId().getExecution().getRunId())
              .build();
        } else if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
            || reusePolicy == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE) {
          return throwDuplicatedWorkflow(startRequest, existing);
        } else if (reusePolicy
                == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY
            && (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED
                || status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW)) {
          return throwDuplicatedWorkflow(startRequest, existing);
        }
      }

      Optional<TestServiceRetryState> retryState;
      Optional<Failure> lastFailure = Optional.empty();
      if (startRequest.hasRetryPolicy()) {
        Duration expirationInterval =
            ProtobufTimeUtils.toJavaDuration(startRequest.getWorkflowExecutionTimeout());
        retryState = newRetryStateLocked(startRequest.getRetryPolicy(), expirationInterval);
        if (retryState.isPresent()) {
          lastFailure = retryState.get().getPreviousRunFailure();
        }
      } else {
        retryState = Optional.empty();
      }

      return startWorkflowExecutionNoRunningCheckLocked(
          startRequest,
          newRunId,
          // it's the first execution in the continue-as-new chain, so firstExecutionRunId =
          // newRunId
          newRunId,
          Optional.empty(),
          retryState,
          backoffStartInterval,
          null,
          lastFailure,
          parent,
          parentChildInitiatedEventId,
          withStart,
          workflowId);
    } finally {
      lock.unlock();
    }
  }

  private Optional<TestServiceRetryState> newRetryStateLocked(
      RetryPolicy retryPolicy, Duration expirationInterval) {
    Timestamp expirationTime =
        expirationInterval.isZero()
            ? Timestamps.fromNanos(0)
            : Timestamps.add(
                store.currentTime(), ProtobufTimeUtils.toProtoDuration(expirationInterval));
    return Optional.of(new TestServiceRetryState(retryPolicy, expirationTime));
  }

  private StartWorkflowExecutionResponse throwDuplicatedWorkflow(
      StartWorkflowExecutionRequest startRequest, TestWorkflowMutableState existing) {
    WorkflowExecution execution = existing.getExecutionId().getExecution();
    WorkflowExecutionAlreadyStartedFailure error =
        WorkflowExecutionAlreadyStartedFailure.newBuilder()
            .setRunId(execution.getRunId())
            .setStartRequestId(startRequest.getRequestId())
            .build();
    throw StatusUtils.newException(
        Status.ALREADY_EXISTS.withDescription(
            String.format(
                "WorkflowId: %s, " + "RunId: %s", execution.getWorkflowId(), execution.getRunId())),
        error,
        WorkflowExecutionAlreadyStartedFailure.getDescriptor());
  }

  private StartWorkflowExecutionResponse startWorkflowExecutionNoRunningCheckLocked(
      StartWorkflowExecutionRequest startRequest,
      @Nonnull String runId,
      @Nonnull String firstExecutionRunId,
      Optional<String> continuedExecutionRunId,
      Optional<TestServiceRetryState> retryState,
      Duration backoffStartInterval,
      Payloads lastCompletionResult,
      Optional<Failure> lastFailure,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId,
      @Nullable Consumer<TestWorkflowMutableState> withStart,
      WorkflowId workflowId) {
    String namespace = startRequest.getNamespace();
    TestWorkflowMutableState mutableState =
        new TestWorkflowMutableStateImpl(
            startRequest,
            firstExecutionRunId,
            runId,
            retryState,
            backoffStartInterval,
            lastCompletionResult,
            lastFailure,
            parent,
            parentChildInitiatedEventId,
            continuedExecutionRunId,
            this,
            store,
            visibilityStore,
            nexusEndpointStore,
            selfAdvancingTimer);
    WorkflowExecution execution = mutableState.getExecutionId().getExecution();
    ExecutionId executionId = new ExecutionId(namespace, execution);
    executionsByWorkflowId.put(workflowId, mutableState);
    executions.put(executionId, mutableState);

    PollWorkflowTaskQueueRequest eagerWorkflowTaskPollRequest =
        startRequest.getRequestEagerExecution()
            ? PollWorkflowTaskQueueRequest.newBuilder()
                .setIdentity(startRequest.getIdentity())
                .setNamespace(startRequest.getNamespace())
                .setTaskQueue(startRequest.getTaskQueue())
                .build()
            : null;

    @Nullable
    PollWorkflowTaskQueueResponse eagerWorkflowTask =
        mutableState.startWorkflow(
            continuedExecutionRunId.isPresent(), eagerWorkflowTaskPollRequest, withStart);
    StartWorkflowExecutionResponse.Builder response =
        StartWorkflowExecutionResponse.newBuilder().setRunId(execution.getRunId()).setStarted(true);
    if (eagerWorkflowTask != null) {
      response.setEagerWorkflowTask(eagerWorkflowTask);
    }
    return response.build();
  }

  @Override
  public void getWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest getRequest,
      StreamObserver<GetWorkflowExecutionHistoryResponse> responseObserver) {
    ExecutionId executionId = new ExecutionId(getRequest.getNamespace(), getRequest.getExecution());
    executor.execute(
        // preserving gRPC context deadline between threads
        Context.current()
            .wrap(
                () -> {
                  try {
                    TestWorkflowMutableState mutableState = getMutableState(executionId);
                    responseObserver.onNext(
                        store.getWorkflowExecutionHistory(
                            mutableState.getExecutionId(),
                            getRequest,
                            // We explicitly don't try to respond inside the context deadline.
                            // If we try to fit into the context deadline, the deadline may be not
                            // expired on the client side and an empty response will lead to a new
                            // request, making the client hammer the server at the tail end of the
                            // deadline.
                            // So this call is designed to wait fully till the end of the
                            // context deadline and throw DEADLINE_EXCEEDED if the deadline is less
                            // than 20s.
                            // If it's longer than 20 seconds - we return an empty result.
                            Deadline.after(20, TimeUnit.SECONDS)));
                    responseObserver.onCompleted();
                  } catch (StatusRuntimeException e) {
                    if (e.getStatus().getCode() == Status.Code.INTERNAL) {
                      log.error("unexpected", e);
                    }
                    responseObserver.onError(e);
                  } catch (Exception e) {
                    log.error("unexpected", e);
                    responseObserver.onError(e);
                  }
                }));
  }

  private <T> T pollTaskQueue(Context ctx, Future<T> futureValue)
      throws ExecutionException, InterruptedException {
    final Context.CancellationListener canceler = context -> futureValue.cancel(true);
    ctx.addListener(canceler, this.backgroundScheduler);
    try {
      return futureValue.get();
    } finally {
      ctx.removeListener(canceler);
    }
  }

  @Override
  public void pollWorkflowTaskQueue(
      PollWorkflowTaskQueueRequest pollRequest,
      StreamObserver<PollWorkflowTaskQueueResponse> responseObserver) {
    try (Context.CancellableContext ctx = deadlineCtx(getLongPollDeadline())) {
      PollWorkflowTaskQueueResponse.Builder task;
      try {
        task = pollTaskQueue(ctx, store.pollWorkflowTaskQueue(pollRequest));
      } catch (ExecutionException e) {
        responseObserver.onError(e);
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        responseObserver.onNext(PollWorkflowTaskQueueResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      } catch (CancellationException e) {
        responseObserver.onNext(PollWorkflowTaskQueueResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      }

      ExecutionId executionId =
          new ExecutionId(pollRequest.getNamespace(), task.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      try {
        mutableState.startWorkflowTask(task, pollRequest);
        // The task always has the original task queue that was created as part of the response.
        // This may be a different task queue than the task queue it was scheduled on, as in the
        // case of sticky execution.
        task.setWorkflowExecutionTaskQueue(mutableState.getStartRequest().getTaskQueue());
        PollWorkflowTaskQueueResponse response = task.build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
          if (log.isDebugEnabled()) {
            log.debug("Skipping outdated workflow task for " + executionId, e);
          }
          // The real service doesn't return this call on outdated task.
          // For simplicity, we return an empty result here.
          responseObserver.onNext(PollWorkflowTaskQueueResponse.getDefaultInstance());
          responseObserver.onCompleted();
        } else {
          if (e.getStatus().getCode() == Status.Code.INTERNAL) {
            log.error("unexpected", e);
          }
          responseObserver.onError(e);
        }
      }
    }
  }

  @Override
  public void respondWorkflowTaskCompleted(
      RespondWorkflowTaskCompletedRequest request,
      StreamObserver<RespondWorkflowTaskCompletedResponse> responseObserver) {
    try {
      WorkflowTaskToken taskToken = WorkflowTaskToken.fromBytes(request.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
      mutableState.completeWorkflowTask(taskToken.getHistorySize(), request);
      responseObserver.onNext(RespondWorkflowTaskCompletedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    } catch (Throwable e) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription(Throwables.getStackTraceAsString(e))
              .withCause(e)
              .asRuntimeException());
    }
  }

  @Override
  public void respondWorkflowTaskFailed(
      RespondWorkflowTaskFailedRequest failedRequest,
      StreamObserver<RespondWorkflowTaskFailedResponse> responseObserver) {
    try {
      WorkflowTaskToken taskToken = WorkflowTaskToken.fromBytes(failedRequest.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(taskToken.getExecutionId());
      mutableState.failWorkflowTask(failedRequest);
      responseObserver.onNext(RespondWorkflowTaskFailedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void getSystemInfo(
      GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> responseObserver) {
    responseObserver.onNext(
        GetSystemInfoResponse.newBuilder()
            .setCapabilities(
                // These are the capabilities I could verify the test server supports
                GetSystemInfoResponse.Capabilities.newBuilder()
                    .setSdkMetadata(true)
                    .setSignalAndQueryHeader(true)
                    .setEncodedFailureAttributes(true)
                    .setEagerWorkflowStart(true)
                    .build())
            .build());
    responseObserver.onCompleted();
  }

  private Context.CancellableContext deadlineCtx(Deadline deadline) {
    return Context.current().withDeadline(deadline, this.backgroundScheduler);
  }

  @Override
  public void pollActivityTaskQueue(
      PollActivityTaskQueueRequest pollRequest,
      StreamObserver<PollActivityTaskQueueResponse> responseObserver) {
    try (Context.CancellableContext ctx = deadlineCtx(getLongPollDeadline())) {

      PollActivityTaskQueueResponse.Builder task;
      try {
        task = pollTaskQueue(ctx, store.pollActivityTaskQueue(pollRequest));
      } catch (ExecutionException e) {
        responseObserver.onError(e);
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        responseObserver.onNext(PollActivityTaskQueueResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      } catch (CancellationException e) {
        responseObserver.onNext(PollActivityTaskQueueResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      }

      ExecutionId executionId =
          new ExecutionId(pollRequest.getNamespace(), task.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      try {
        mutableState.startActivityTask(task, pollRequest);
        responseObserver.onNext(task.build());
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
          if (log.isDebugEnabled()) {
            log.debug("Skipping outdated activity task for " + executionId, e);
          }
          responseObserver.onNext(PollActivityTaskQueueResponse.getDefaultInstance());
          responseObserver.onCompleted();
        } else {
          if (e.getStatus().getCode() == Status.Code.INTERNAL) {
            log.error("unexpected", e);
          }
          responseObserver.onError(e);
        }
      }
    }
  }

  @Override
  public void recordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest,
      StreamObserver<RecordActivityTaskHeartbeatResponse> responseObserver) {
    try {
      ActivityTaskToken activityTaskToken =
          ActivityTaskToken.fromBytes(heartbeatRequest.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(activityTaskToken.getExecutionId());
      boolean cancelRequested =
          mutableState.heartbeatActivityTask(
              activityTaskToken.getScheduledEventId(), heartbeatRequest.getDetails());
      responseObserver.onNext(
          RecordActivityTaskHeartbeatResponse.newBuilder()
              .setCancelRequested(cancelRequested)
              .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void recordActivityTaskHeartbeatById(
      RecordActivityTaskHeartbeatByIdRequest heartbeatRequest,
      StreamObserver<RecordActivityTaskHeartbeatByIdResponse> responseObserver) {
    try {
      ExecutionId execution =
          new ExecutionId(
              heartbeatRequest.getNamespace(),
              heartbeatRequest.getWorkflowId(),
              heartbeatRequest.getRunId());
      TestWorkflowMutableState mutableState = getMutableState(execution);
      boolean cancelRequested =
          mutableState.heartbeatActivityTaskById(
              heartbeatRequest.getActivityId(),
              heartbeatRequest.getDetails(),
              heartbeatRequest.getIdentity());
      responseObserver.onNext(
          RecordActivityTaskHeartbeatByIdResponse.newBuilder()
              .setCancelRequested(cancelRequested)
              .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondActivityTaskCompleted(
      RespondActivityTaskCompletedRequest completeRequest,
      StreamObserver<RespondActivityTaskCompletedResponse> responseObserver) {
    try {
      ActivityTaskToken activityTaskToken =
          ActivityTaskToken.fromBytes(completeRequest.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(activityTaskToken.getExecutionId());
      mutableState.completeActivityTask(activityTaskToken.getScheduledEventId(), completeRequest);
      responseObserver.onNext(RespondActivityTaskCompletedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondActivityTaskCompletedById(
      RespondActivityTaskCompletedByIdRequest completeRequest,
      StreamObserver<RespondActivityTaskCompletedByIdResponse> responseObserver) {
    try {
      ExecutionId executionId =
          new ExecutionId(
              completeRequest.getNamespace(),
              completeRequest.getWorkflowId(),
              completeRequest.getRunId());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      mutableState.completeActivityTaskById(completeRequest.getActivityId(), completeRequest);
      responseObserver.onNext(RespondActivityTaskCompletedByIdResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondActivityTaskFailed(
      RespondActivityTaskFailedRequest failRequest,
      StreamObserver<RespondActivityTaskFailedResponse> responseObserver) {
    try {
      ActivityTaskToken activityTaskToken = ActivityTaskToken.fromBytes(failRequest.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(activityTaskToken.getExecutionId());
      mutableState.failActivityTask(activityTaskToken.getScheduledEventId(), failRequest);
      responseObserver.onNext(RespondActivityTaskFailedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondActivityTaskFailedById(
      RespondActivityTaskFailedByIdRequest failRequest,
      StreamObserver<RespondActivityTaskFailedByIdResponse> responseObserver) {
    try {
      ExecutionId executionId =
          new ExecutionId(
              failRequest.getNamespace(), failRequest.getWorkflowId(), failRequest.getRunId());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      mutableState.failActivityTaskById(failRequest.getActivityId(), failRequest);
      responseObserver.onNext(RespondActivityTaskFailedByIdResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondActivityTaskCanceled(
      RespondActivityTaskCanceledRequest canceledRequest,
      StreamObserver<RespondActivityTaskCanceledResponse> responseObserver) {
    try {
      ActivityTaskToken activityTaskToken =
          ActivityTaskToken.fromBytes(canceledRequest.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(activityTaskToken.getExecutionId());
      mutableState.cancelActivityTask(activityTaskToken.getScheduledEventId(), canceledRequest);
      responseObserver.onNext(RespondActivityTaskCanceledResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondActivityTaskCanceledById(
      RespondActivityTaskCanceledByIdRequest canceledRequest,
      StreamObserver<RespondActivityTaskCanceledByIdResponse> responseObserver) {
    try {
      ExecutionId executionId =
          new ExecutionId(
              canceledRequest.getNamespace(),
              canceledRequest.getWorkflowId(),
              canceledRequest.getRunId());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      mutableState.cancelActivityTaskById(canceledRequest.getActivityId(), canceledRequest);
      responseObserver.onNext(RespondActivityTaskCanceledByIdResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void pollNexusTaskQueue(
      PollNexusTaskQueueRequest request,
      StreamObserver<PollNexusTaskQueueResponse> responseObserver) {
    try (Context.CancellableContext ctx = deadlineCtx(getLongPollDeadline())) {
      TestWorkflowStore.NexusTask task;
      try {
        task = pollTaskQueue(ctx, store.pollNexusTaskQueue(request));
      } catch (ExecutionException e) {
        responseObserver.onError(e);
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        responseObserver.onNext(PollNexusTaskQueueResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      } catch (CancellationException e) {
        responseObserver.onNext(PollNexusTaskQueueResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      }

      String taskTimeout =
          String.valueOf(Timestamps.between(store.currentTime(), task.getDeadline()).getSeconds());
      Request.Builder req =
          task.getTask().getRequestBuilder().putHeader("Request-Timeout", taskTimeout);
      PollNexusTaskQueueResponse.Builder resp = task.getTask().setRequest(req);

      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void respondNexusTaskCompleted(
      RespondNexusTaskCompletedRequest request,
      StreamObserver<RespondNexusTaskCompletedResponse> responseObserver) {
    try {
      NexusTaskToken tt = NexusTaskToken.fromBytes(request.getTaskToken());
      TestWorkflowMutableState mutableState =
          getMutableState(tt.getOperationRef().getExecutionId());
      if (!mutableState.validateOperationTaskToken(tt)) {
        responseObserver.onNext(RespondNexusTaskCompletedResponse.getDefaultInstance());
        responseObserver.onCompleted();
        return;
      }

      if (request.getResponse().hasCancelOperation()) {
        mutableState.cancelNexusOperation(tt.getOperationRef(), null);
      } else if (request.getResponse().hasStartOperation()) {
        StartOperationResponse startResp = request.getResponse().getStartOperation();
        if (startResp.hasOperationError()) {
          UnsuccessfulOperationError opError = startResp.getOperationError();
          Failure.Builder b = Failure.newBuilder().setMessage(opError.getFailure().getMessage());

          if (startResp.getOperationError().getOperationState().equals("canceled")) {
            b.setCanceledFailureInfo(
                CanceledFailureInfo.newBuilder()
                    .setDetails(nexusFailureMetadataToPayloads(opError.getFailure())));
            mutableState.cancelNexusOperation(tt.getOperationRef(), b.build());
          } else {
            b.setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder()
                    .setType("NexusOperationFailure")
                    .setDetails(nexusFailureMetadataToPayloads(opError.getFailure()))
                    .setNonRetryable(true));
            mutableState.failNexusOperation(tt.getOperationRef(), b.build());
          }
        } else if (startResp.hasAsyncSuccess()) {
          // Start event is only recorded for async success
          mutableState.startNexusOperation(
              tt.getOperationRef().getScheduledEventId(),
              request.getIdentity(),
              startResp.getAsyncSuccess());
        } else if (startResp.hasSyncSuccess()) {
          mutableState.completeNexusOperation(
              tt.getOperationRef(), startResp.getSyncSuccess().getPayload());
        } else {
          throw Status.INVALID_ARGUMENT
              .withDescription("Expected success or OperationError to be set on request.")
              .asRuntimeException();
        }
      } else {
        throw Status.INVALID_ARGUMENT
            .withDescription("Expected StartOperation or CancelOperation to be set on request.")
            .asRuntimeException();
      }
      responseObserver.onNext(RespondNexusTaskCompletedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondNexusTaskFailed(
      RespondNexusTaskFailedRequest request,
      StreamObserver<RespondNexusTaskFailedResponse> responseObserver) {
    try {
      if (!request.hasError()) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Nexus handler error not set on RespondNexusTaskFailedRequest")
            .asRuntimeException();
      }
      NexusTaskToken tt = NexusTaskToken.fromBytes(request.getTaskToken());
      TestWorkflowMutableState mutableState =
          getMutableState(tt.getOperationRef().getExecutionId());
      if (mutableState.validateOperationTaskToken(tt)) {
        Failure failure = handlerErrorToFailure(request.getError());
        mutableState.failNexusOperation(tt.getOperationRef(), failure);
      }
      responseObserver.onNext(RespondNexusTaskFailedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  public void completeNexusOperation(NexusOperationRef ref, HistoryEvent completionEvent) {
    TestWorkflowMutableState target = getMutableState(ref.getExecutionId());

    switch (completionEvent.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        Payloads result =
            completionEvent.getWorkflowExecutionCompletedEventAttributes().getResult();
        // All of our SDKs support returning a single value from workflows, we can safely ignore the
        // rest of the payloads. Additionally, even if a workflow could return more than a single
        // value,
        // Nexus does not support it.
        Payload p =
            (result.getPayloadsCount() > 0) ? result.getPayloads(0) : Payload.getDefaultInstance();
        target.completeNexusOperation(ref, p);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        Failure f =
            Failure.newBuilder()
                .setMessage(
                    completionEvent
                        .getWorkflowExecutionFailedEventAttributes()
                        .getFailure()
                        .getMessage())
                .build();
        target.failNexusOperation(ref, f);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        Failure canceled =
            Failure.newBuilder()
                .setMessage("operation canceled")
                .setApplicationFailureInfo(
                    ApplicationFailureInfo.newBuilder().setNonRetryable(true))
                .build();
        target.cancelNexusOperation(ref, canceled);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        Failure terminated =
            Failure.newBuilder()
                .setMessage("operation terminated")
                .setApplicationFailureInfo(
                    ApplicationFailureInfo.newBuilder().setNonRetryable(true))
                .build();
        target.failNexusOperation(ref, terminated);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        Failure timedOut =
            Failure.newBuilder()
                .setMessage("operation exceeded internal timeout")
                .setApplicationFailureInfo(
                    ApplicationFailureInfo.newBuilder().setNonRetryable(true))
                .build();
        target.failNexusOperation(ref, timedOut);
        break;
      default:
        throw Status.INTERNAL
            .withDescription("invalid workflow execution status: " + completionEvent.getEventType())
            .asRuntimeException();
    }
  }

  private static Failure handlerErrorToFailure(HandlerError err) {
    return Failure.newBuilder()
        .setMessage(err.getFailure().getMessage())
        .setApplicationFailureInfo(
            ApplicationFailureInfo.newBuilder()
                .setType(err.getErrorType())
                .setDetails(nexusFailureMetadataToPayloads(err.getFailure())))
        .build();
  }

  private static Payloads nexusFailureMetadataToPayloads(io.temporal.api.nexus.v1.Failure failure) {
    Map<String, ByteString> metadata =
        failure.getMetadataMap().entrySet().stream()
            .collect(
                Collectors.toMap(Map.Entry::getKey, e -> ByteString.copyFromUtf8(e.getValue())));
    return Payloads.newBuilder()
        .addPayloads(Payload.newBuilder().putAllMetadata(metadata).setData(failure.getDetails()))
        .build();
  }

  @Override
  public void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      StreamObserver<RequestCancelWorkflowExecutionResponse> responseObserver) {
    try {
      requestCancelWorkflowExecution(cancelRequest, Optional.empty());
      responseObserver.onNext(RequestCancelWorkflowExecutionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      Optional<TestWorkflowMutableStateImpl.CancelExternalWorkflowExecutionCallerInfo> callerInfo) {
    ExecutionId executionId =
        new ExecutionId(cancelRequest.getNamespace(), cancelRequest.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.requestCancelWorkflowExecution(cancelRequest, callerInfo);
  }

  @Override
  public void terminateWorkflowExecution(
      TerminateWorkflowExecutionRequest request,
      StreamObserver<TerminateWorkflowExecutionResponse> responseObserver) {
    try {
      terminateWorkflowExecution(request);
      responseObserver.onNext(TerminateWorkflowExecutionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  private void terminateWorkflowExecution(TerminateWorkflowExecutionRequest request) {
    ExecutionId executionId =
        new ExecutionId(request.getNamespace(), request.getWorkflowExecution());
    TestWorkflowMutableState mutableState = getMutableState(executionId);
    mutableState.terminateWorkflowExecution(request);
  }

  @Override
  public void signalWorkflowExecution(
      SignalWorkflowExecutionRequest signalRequest,
      StreamObserver<SignalWorkflowExecutionResponse> responseObserver) {
    try {
      ExecutionId executionId =
          new ExecutionId(signalRequest.getNamespace(), signalRequest.getWorkflowExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      mutableState.signal(signalRequest);
      responseObserver.onNext(SignalWorkflowExecutionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void updateWorkflowExecution(
      UpdateWorkflowExecutionRequest request,
      StreamObserver<UpdateWorkflowExecutionResponse> responseObserver) {
    try (Context.CancellableContext ctx = deadlineCtx(getUpdatePollDeadline())) {
      Context toRestore = ctx.attach();
      try {
        ExecutionId executionId =
            new ExecutionId(request.getNamespace(), request.getWorkflowExecution());
        TestWorkflowMutableState mutableState = getMutableState(executionId);
        @Nullable Deadline deadline = Context.current().getDeadline();
        TestWorkflowMutableStateImpl.UpdateHandle updateHandle =
            mutableState.updateWorkflowExecution(request, deadline);
        UpdateWorkflowExecutionResponse response =
            waitForUpdateResponse(request, deadline, updateHandle);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        handleStatusRuntimeException(e, responseObserver);
      } finally {
        ctx.detach(toRestore);
      }
    }
  }

  UpdateWorkflowExecutionResponse waitForUpdateResponse(
      UpdateWorkflowExecutionRequest request,
      Deadline deadline,
      TestWorkflowMutableStateImpl.UpdateHandle updateHandle) {
    try {
      UpdateWorkflowExecutionLifecycleStage reachedStage =
          updateHandle.waitForStage(
              request.getWaitPolicy().getLifecycleStage(),
              deadline.timeRemaining(TimeUnit.MILLISECONDS),
              TimeUnit.MILLISECONDS);
      UpdateWorkflowExecutionResponse.Builder response =
          UpdateWorkflowExecutionResponse.newBuilder()
              .setUpdateRef(updateHandle.getRef())
              .setStage(reachedStage);
      if (reachedStage == UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED) {
        response.setOutcome(updateHandle.getOutcomeNow());
      }
      return response.build();
    } catch (TimeoutException e) {
      UpdateWorkflowExecutionLifecycleStage stage = updateHandle.getStage();
      UpdateWorkflowExecutionResponse.Builder response =
          UpdateWorkflowExecutionResponse.newBuilder()
              .setUpdateRef(updateHandle.getRef())
              .setStage(stage);
      if (stage
          == UpdateWorkflowExecutionLifecycleStage
              .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED) {
        response.setOutcome(updateHandle.getOutcomeNow());
      }
      return response.build();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof StatusRuntimeException) {
        throw (StatusRuntimeException) cause;
      }
      throw Status.INTERNAL
          .withCause(cause)
          .withDescription(cause.getMessage())
          .asRuntimeException();
    }
  }

  @Override
  public void pollWorkflowExecutionUpdate(
      PollWorkflowExecutionUpdateRequest request,
      StreamObserver<PollWorkflowExecutionUpdateResponse> responseObserver) {
    try (Context.CancellableContext ctx = deadlineCtx(getUpdatePollDeadline())) {
      Context toRestore = ctx.attach();
      try {
        ExecutionId executionId =
            new ExecutionId(request.getNamespace(), request.getUpdateRef().getWorkflowExecution());
        TestWorkflowMutableState mutableState = getMutableState(executionId);
        @Nullable Deadline deadline = Context.current().getDeadline();
        PollWorkflowExecutionUpdateResponse response =
            mutableState.pollUpdateWorkflowExecution(request, deadline);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        handleStatusRuntimeException(e, responseObserver);
      } finally {
        ctx.detach(toRestore);
      }
    }
  }

  @Override
  public void executeMultiOperation(
      ExecuteMultiOperationRequest request,
      StreamObserver<ExecuteMultiOperationResponse> responseObserver) {
    try {
      if (request.getOperationsCount() != 2) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Operations have to be exactly [Start, Update].")
            .asRuntimeException();
      }

      StartWorkflowExecutionRequest startRequest;
      ExecuteMultiOperationRequest.Operation firstOperation = request.getOperations(0);
      if (firstOperation.getOperationCase() != START_WORKFLOW) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Operations have to be exactly [Start, Update].")
            .asRuntimeException();
      }
      startRequest = firstOperation.getStartWorkflow();

      if (!startRequest.getCronSchedule().isEmpty()) {
        throw multiOperationExecutionFailure(
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.INVALID_ARGUMENT.getCode().value())
                .setMessage("INVALID_ARGUMENT: CronSchedule is not allowed.")
                .build(),
            null);
      }

      if (startRequest.getRequestEagerExecution()) {
        throw multiOperationExecutionFailure(
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.INVALID_ARGUMENT.getCode().value())
                .setMessage("INVALID_ARGUMENT: RequestEagerExecution is not supported.")
                .build(),
            null);
      }

      UpdateWorkflowExecutionRequest updateRequest;
      ExecuteMultiOperationRequest.Operation secondOperation = request.getOperations(1);
      if (secondOperation.getOperationCase() != UPDATE_WORKFLOW) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Operations have to be exactly [Start, Update].")
            .asRuntimeException();
      }
      updateRequest = secondOperation.getUpdateWorkflow();

      if (!updateRequest.getWorkflowExecution().getRunId().isEmpty()) {
        throw multiOperationExecutionFailure(
            null, // start aborted
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.INVALID_ARGUMENT.getCode().value())
                .setMessage("INVALID_ARGUMENT: RunId is not allowed.")
                .build());
      }

      if (!updateRequest.getFirstExecutionRunId().isEmpty()) {
        throw multiOperationExecutionFailure(
            null, // start aborted
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.INVALID_ARGUMENT.getCode().value())
                .setMessage("INVALID_ARGUMENT: FirstExecutionRunId is not allowed.")
                .build());
      }

      if (!startRequest
          .getWorkflowId()
          .equals(updateRequest.getWorkflowExecution().getWorkflowId())) {
        throw multiOperationExecutionFailure(
            null, // start aborted
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.INVALID_ARGUMENT.getCode().value())
                .setMessage(
                    "INVALID_ARGUMENT: WorkflowId is not consistent with previous operation(s)")
                .build());
      }

      if (startRequest.hasWorkflowStartDelay()) {
        throw multiOperationExecutionFailure(
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.INVALID_ARGUMENT.getCode().value())
                .setMessage("INVALID_ARGUMENT: WorkflowStartDelay is not supported.")
                .build(),
            null);
      }

      AtomicReference<TestWorkflowMutableStateImpl.UpdateHandle> updateHandle =
          new AtomicReference<>();
      Consumer<TestWorkflowMutableState> applyUpdate =
          ms -> {
            @Nullable Deadline deadline = Context.current().getDeadline();
            try {
              updateHandle.set(ms.updateWorkflowExecution(updateRequest, deadline));
            } catch (StatusRuntimeException e) {
              throw multiOperationExecutionFailure(
                  null, // ie start aborted
                  MultiOperationExecutionFailure.OperationStatus.newBuilder()
                      .setCode(e.getStatus().getCode().value())
                      .setMessage(e.getMessage())
                      .build());
            }
          };

      StartWorkflowExecutionResponse startResult;
      try {
        startResult =
            startWorkflowExecutionImpl(
                startRequest, Duration.ZERO, Optional.empty(), OptionalLong.empty(), applyUpdate);
      } catch (StatusRuntimeException e) {
        if (StatusUtils.hasFailure(e, MultiOperationExecutionFailure.class)) {
          throw e;
        }

        throw multiOperationExecutionFailure(
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(e.getStatus().getCode().value())
                .setMessage(e.getMessage())
                .build(),
            null); // ie update aborted
      }

      // if the workflow wasn't started, only send the Update request
      if (!startResult.getStarted()) {
        ExecutionId executionId =
            new ExecutionId(request.getNamespace(), updateRequest.getWorkflowExecution());
        TestWorkflowMutableState mutableState = getMutableState(executionId);
        applyUpdate.accept(mutableState);
      }

      @Nullable Deadline deadline = Context.current().getDeadline();
      UpdateWorkflowExecutionResponse updateResult =
          waitForUpdateResponse(updateRequest, deadline, updateHandle.get());

      ExecuteMultiOperationResponse response =
          ExecuteMultiOperationResponse.newBuilder()
              .addResponses(
                  ExecuteMultiOperationResponse.Response.newBuilder().setStartWorkflow(startResult))
              .addResponses(
                  ExecuteMultiOperationResponse.Response.newBuilder()
                      .setUpdateWorkflow(updateResult))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  private StatusRuntimeException multiOperationExecutionFailure(
      MultiOperationExecutionFailure.OperationStatus... operationStatuses) {
    Status status = null;
    for (int i = 0; i < operationStatuses.length; i++) {
      MultiOperationExecutionFailure.OperationStatus operationStatus = operationStatuses[i];
      if (operationStatus == null) {
        // convert to aborted failure
        operationStatuses[i] =
            MultiOperationExecutionFailure.OperationStatus.newBuilder()
                .setCode(Status.ABORTED.getCode().value())
                .setMessage("Operation was aborted.")
                .addDetails(
                    ProtoUtils.packAny(
                        MultiOperationExecutionAborted.newBuilder().build(),
                        MultiOperationExecutionAborted.getDescriptor()))
                .build();
        continue;
      }
      if (status != null) {
        throw new IllegalArgumentException(
            "exactly one non-null operation status must be specified");
      }
      status = Status.fromCodeValue(operationStatus.getCode());
    }
    if (status == null) {
      throw new IllegalArgumentException("exactly one non-null operation status must be specified");
    }

    return StatusUtils.newException(
        status.withDescription("MultiOperation could not be executed"),
        MultiOperationExecutionFailure.newBuilder()
            .addAllStatuses(Arrays.asList(operationStatuses))
            .build(),
        MultiOperationExecutionFailure.getDescriptor());
  }

  @Override
  public void signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest r,
      StreamObserver<SignalWithStartWorkflowExecutionResponse> responseObserver) {
    try {
      if (!r.hasTaskQueue()) {
        throw Status.INVALID_ARGUMENT
            .withDescription("request missing required taskQueue field")
            .asRuntimeException();
      }
      if (!r.hasWorkflowType()) {
        throw Status.INVALID_ARGUMENT
            .withDescription("request missing required workflowType field")
            .asRuntimeException();
      }
      ExecutionId executionId = new ExecutionId(r.getNamespace(), r.getWorkflowId(), null);
      TestWorkflowMutableState mutableState = getMutableState(executionId, false);
      SignalWorkflowExecutionRequest signalRequest =
          SignalWorkflowExecutionRequest.newBuilder()
              .setInput(r.getSignalInput())
              .setSignalName(r.getSignalName())
              .setWorkflowExecution(executionId.getExecution())
              .setRequestId(r.getRequestId())
              .setControl(r.getControl())
              .setNamespace(r.getNamespace())
              .setIdentity(r.getIdentity())
              .build();
      if (mutableState != null && !mutableState.isTerminalState()) {
        mutableState.signal(signalRequest);
        responseObserver.onNext(
            SignalWithStartWorkflowExecutionResponse.newBuilder()
                .setRunId(mutableState.getExecutionId().getExecution().getRunId())
                .build());
        responseObserver.onCompleted();
        return;
      }
      StartWorkflowExecutionRequest.Builder startRequest =
          StartWorkflowExecutionRequest.newBuilder()
              .setRequestId(r.getRequestId())
              .setInput(r.getInput())
              .setWorkflowExecutionTimeout(r.getWorkflowExecutionTimeout())
              .setWorkflowRunTimeout(r.getWorkflowRunTimeout())
              .setWorkflowTaskTimeout(r.getWorkflowTaskTimeout())
              .setNamespace(r.getNamespace())
              .setTaskQueue(r.getTaskQueue())
              .setWorkflowId(r.getWorkflowId())
              .setWorkflowIdReusePolicy(r.getWorkflowIdReusePolicy())
              .setIdentity(r.getIdentity())
              .setWorkflowType(r.getWorkflowType())
              .setCronSchedule(r.getCronSchedule())
              .setRequestId(r.getRequestId());
      if (r.hasRetryPolicy()) {
        startRequest.setRetryPolicy(r.getRetryPolicy());
      }
      if (r.hasHeader()) {
        startRequest.setHeader(r.getHeader());
      }
      if (r.hasMemo()) {
        startRequest.setMemo(r.getMemo());
      }
      if (r.hasSearchAttributes()) {
        startRequest.setSearchAttributes(r.getSearchAttributes());
      }
      if (r.hasWorkflowStartDelay()) {
        startRequest.setWorkflowStartDelay(r.getWorkflowStartDelay());
      }

      StartWorkflowExecutionResponse startResult =
          startWorkflowExecutionImpl(
              startRequest.build(),
              Duration.ZERO,
              Optional.empty(),
              OptionalLong.empty(),
              ms -> {
                ms.signal(signalRequest);
              });
      responseObserver.onNext(
          SignalWithStartWorkflowExecutionResponse.newBuilder()
              .setRunId(startResult.getRunId())
              .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  public void signalExternalWorkflowExecution(
      String signalId,
      SignalExternalWorkflowExecutionCommandAttributes commandAttributes,
      TestWorkflowMutableState source) {
    String namespace;
    if (commandAttributes.getNamespace().isEmpty()) {
      namespace = source.getExecutionId().getNamespace();
    } else {
      namespace = commandAttributes.getNamespace();
    }
    ExecutionId executionId = new ExecutionId(namespace, commandAttributes.getExecution());
    TestWorkflowMutableState mutableState;
    try {
      mutableState = getMutableState(executionId);
      mutableState.signalFromWorkflow(commandAttributes);
      source.completeSignalExternalWorkflowExecution(
          signalId, mutableState.getExecutionId().getExecution().getRunId());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        source.failSignalExternalWorkflowExecution(
            signalId,
            SignalExternalWorkflowExecutionFailedCause
                .SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_CAUSE_EXTERNAL_WORKFLOW_EXECUTION_NOT_FOUND);
      } else {
        throw e;
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
      ContinueAsNewWorkflowExecutionCommandAttributes ca,
      WorkflowExecutionContinuedAsNewEventAttributes ea,
      Optional<TestServiceRetryState> retryState,
      String identity,
      ExecutionId continuedExecutionId,
      String firstExecutionRunId,
      Optional<TestWorkflowMutableState> parent,
      OptionalLong parentChildInitiatedEventId) {
    StartWorkflowExecutionRequest.Builder startRequestBuilder =
        StartWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setWorkflowType(ea.getWorkflowType())
            .setWorkflowRunTimeout(ea.getWorkflowRunTimeout())
            .setWorkflowTaskTimeout(ea.getWorkflowTaskTimeout())
            .setNamespace(continuedExecutionId.getNamespace())
            .setTaskQueue(ea.getTaskQueue())
            .setWorkflowId(continuedExecutionId.getWorkflowId().getWorkflowId())
            .setWorkflowIdReusePolicy(previousRunStartRequest.getWorkflowIdReusePolicy())
            .setIdentity(identity)
            .setCronSchedule(previousRunStartRequest.getCronSchedule());
    // TODO: Service doesn't perform this copy.
    // See https://github.com/temporalio/temporal/issues/5249
    //    if (previousRunStartRequest.hasRetryPolicy()) {
    //      startRequestBuilder.setRetryPolicy(previousRunStartRequest.getRetryPolicy());
    //    }
    if (previousRunStartRequest.getCompletionCallbacksCount() > 0) {
      startRequestBuilder.addAllCompletionCallbacks(
          previousRunStartRequest.getCompletionCallbacksList());
    }
    if (ca.hasRetryPolicy()) {
      startRequestBuilder.setRetryPolicy(ca.getRetryPolicy());
    }
    if (ea.hasInput()) {
      startRequestBuilder.setInput(ea.getInput());
    }
    if (ea.hasHeader()) {
      startRequestBuilder.setHeader(ea.getHeader());
    }
    StartWorkflowExecutionRequest startRequest = startRequestBuilder.build();
    lock.lock();
    Optional<Failure> lastFail =
        ea.hasFailure()
            ? Optional.of(ea.getFailure())
            : retryState.flatMap(TestServiceRetryState::getPreviousRunFailure);
    try {
      StartWorkflowExecutionResponse response =
          startWorkflowExecutionNoRunningCheckLocked(
              startRequest,
              ea.getNewExecutionRunId(),
              firstExecutionRunId,
              Optional.of(continuedExecutionId.getExecution().getRunId()),
              retryState,
              ProtobufTimeUtils.toJavaDuration(ea.getBackoffStartInterval()),
              ea.getLastCompletionResult(),
              lastFail,
              parent,
              parentChildInitiatedEventId,
              null,
              continuedExecutionId.getWorkflowId());
      return response.getRunId();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void listOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest,
      StreamObserver<ListOpenWorkflowExecutionsResponse> responseObserver) {
    try {
      Optional<String> workflowIdFilter;
      if (listRequest.hasExecutionFilter()
          && !listRequest.getExecutionFilter().getWorkflowId().isEmpty()) {
        workflowIdFilter = Optional.of(listRequest.getExecutionFilter().getWorkflowId());
      } else {
        workflowIdFilter = Optional.empty();
      }
      List<WorkflowExecutionInfo> result =
          store.listWorkflows(WorkflowState.OPEN, workflowIdFilter);
      responseObserver.onNext(
          ListOpenWorkflowExecutionsResponse.newBuilder().addAllExecutions(result).build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void listClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest,
      StreamObserver<ListClosedWorkflowExecutionsResponse> responseObserver) {
    try {
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
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void respondQueryTaskCompleted(
      RespondQueryTaskCompletedRequest completeRequest,
      StreamObserver<RespondQueryTaskCompletedResponse> responseObserver) {
    try {
      QueryId queryId = QueryId.fromBytes(completeRequest.getTaskToken());
      TestWorkflowMutableState mutableState = getMutableState(queryId.getExecutionId());
      mutableState.completeQuery(queryId, completeRequest);
      responseObserver.onNext(RespondQueryTaskCompletedResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void queryWorkflow(
      QueryWorkflowRequest queryRequest, StreamObserver<QueryWorkflowResponse> responseObserver) {
    try {
      ExecutionId executionId =
          new ExecutionId(queryRequest.getNamespace(), queryRequest.getExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      @Nullable Deadline deadline = Context.current().getDeadline();
      QueryWorkflowResponse result =
          mutableState.query(
              queryRequest,
              deadline != null ? deadline.timeRemaining(TimeUnit.MILLISECONDS) : Long.MAX_VALUE);
      responseObserver.onNext(result);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void describeWorkflowExecution(
      DescribeWorkflowExecutionRequest request,
      StreamObserver<DescribeWorkflowExecutionResponse> responseObserver) {
    try {
      if (request.getNamespace().isEmpty()) {
        throw createInvalidArgument("Namespace not set on request.");
      }
      if (!request.hasExecution()) {
        throw createInvalidArgument("Execution not set on request.");
      }

      ExecutionId executionId = new ExecutionId(request.getNamespace(), request.getExecution());
      TestWorkflowMutableState mutableState = getMutableState(executionId);
      DescribeWorkflowExecutionResponse result = mutableState.describeWorkflowExecution();
      responseObserver.onNext(result);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  /**
   * This method doesn't make much sense for test server, it accepts all namespaces as existent and
   * registered. so, it's a trivial implementation just returning an info that a namespace is
   * registered irrespectively of the input
   */
  @Override
  public void describeNamespace(
      DescribeNamespaceRequest request,
      StreamObserver<DescribeNamespaceResponse> responseObserver) {
    try {
      if (request.getNamespace().isEmpty()) {
        throw createInvalidArgument("Namespace not set on request.");
      }
      // generating a stable UUID for name
      String namespaceId = UUID.nameUUIDFromBytes(request.getNamespace().getBytes()).toString();
      DescribeNamespaceResponse result =
          DescribeNamespaceResponse.newBuilder()
              .setNamespaceInfo(
                  NamespaceInfo.newBuilder()
                      .setName(request.getNamespace())
                      .setState(NamespaceState.NAMESPACE_STATE_REGISTERED)
                      .setId(namespaceId)
                      .build())
              .build();
      responseObserver.onNext(result);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  private <R> R requireNotNull(String fieldName, R value) {
    if (value == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Missing required field \"" + fieldName + "\".")
          .asRuntimeException();
    }
    return value;
  }

  /**
   * Adds diagnostic data about internal service state to the provided {@link StringBuilder}.
   * Includes histories of all workflow instances stored in the service.
   */
  public void getDiagnostics(StringBuilder result) {
    store.getDiagnostics(result);
  }

  /**
   * @deprecated use {@link TestServiceStubs} and {@link
   *     TestServiceGrpc.TestServiceBlockingStub#getCurrentTime(Empty)}
   */
  @Deprecated
  public long currentTimeMillis() {
    return selfAdvancingTimer.getClock().getAsLong();
  }

  /** Invokes callback after the specified delay according to internal service clock. */
  public void registerDelayedCallback(Duration delay, Runnable r) {
    store.registerDelayedCallback(delay, r);
  }

  /**
   * Disables time skipping. To re-enable call {@link #unlockTimeSkipping(String)}. These calls are
   * counted, so calling unlock does not guarantee that time is going to be skipped immediately as
   * another lock can be holding it.
   *
   * @deprecated use {@link io.temporal.serviceclient.TestServiceStubs} and {@link
   *     io.temporal.api.testservice.v1.TestServiceGrpc.TestServiceBlockingStub#lockTimeSkipping(LockTimeSkippingRequest)}
   */
  @Deprecated
  public void lockTimeSkipping(String caller) {
    selfAdvancingTimer.lockTimeSkipping(caller);
  }

  /**
   * @deprecated use {@link io.temporal.serviceclient.TestServiceStubs} and {@link
   *     io.temporal.api.testservice.v1.TestServiceGrpc.TestServiceBlockingStub#unlockTimeSkipping(UnlockTimeSkippingRequest)}
   */
  @Deprecated
  public void unlockTimeSkipping(String caller) {
    selfAdvancingTimer.unlockTimeSkipping(caller);
  }

  /**
   * Unlocks time skipping and blocks the calling thread until internal clock passes the current +
   * duration time.<br>
   * When the time is reached, locks time skipping and returns.<br>
   * Might not block at all due to time skipping. Or might block if the time skipping lock counter
   * was more than 1.
   *
   * @deprecated use {@link io.temporal.serviceclient.TestServiceStubs} and {@link
   *     io.temporal.api.testservice.v1.TestServiceGrpc.TestServiceBlockingStub#unlockTimeSkippingWithSleep(SleepRequest)}
   */
  @Deprecated
  public void sleep(Duration duration) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    selfAdvancingTimer.schedule(
        duration,
        () -> {
          selfAdvancingTimer.lockTimeSkipping("TestWorkflowService sleep");
          result.complete(null);
        },
        "workflow sleep");
    selfAdvancingTimer.unlockTimeSkipping("TestWorkflowService sleep");
    try {
      result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Temporal server times out task queue long poll calls after 1 minute and returns an empty
   * result. After which the request has to be retried by the client if it wants to continue
   * waiting. We emulate this behavior here.
   *
   * <p>If there is a deadline present, for task queue poll requests server will respond inside the
   * deadline. Note that the latest is not applicable for getWorkflowExecutionHistory() long polls.
   *
   * @return minimum between the context deadline and maximum long poll deadline.
   */
  private Deadline getLongPollDeadline() {
    @Nullable Deadline deadline = Context.current().getDeadline();
    Deadline maximumDeadline =
        Deadline.after(
            WorkflowServiceStubsOptions.DEFAULT_SERVER_LONG_POLL_RPC_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS);
    return deadline != null ? deadline.minimum(maximumDeadline) : maximumDeadline;
  }

  private Deadline getUpdatePollDeadline() {
    @Nullable Deadline deadline = Context.current().getDeadline();
    Deadline maximumDeadline =
        Deadline.after(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
    return deadline != null ? deadline.minimum(maximumDeadline) : maximumDeadline;
  }

  private void handleStatusRuntimeException(
      StatusRuntimeException e, StreamObserver<?> responseObserver) {
    if (e.getStatus().getCode() == Status.Code.INTERNAL) {
      log.error("unexpected", e);
    }
    responseObserver.onError(e);
  }

  /**
   * Creates an in-memory service along with client stubs for use in Java code. See also
   * createServerOnly and createWithNoGrpcServer.
   *
   * @deprecated use {@link TestServer#createServer(boolean)} instead and pass {@code
   *     lockTimeSkipping=false} to emulate the behavior of this method
   */
  @Deprecated
  public TestWorkflowService() {
    this(0, true);
  }

  /**
   * Creates an in-memory service along with client stubs for use in Java code. See also
   * createServerOnly and createWithNoGrpcServer.
   *
   * @deprecated use {@link TestServer#createServer(boolean, long)} instead and pass {@code
   *     lockTimeSkipping=false} to emulate the behavior of this method
   */
  @Deprecated
  public TestWorkflowService(long initialTimeMillis) {
    this(initialTimeMillis, true);
  }

  /**
   * Creates an in-memory service along with client stubs for use in Java code. See also
   * createServerOnly and createWithNoGrpcServer.
   *
   * @deprecated use {@link TestServer#createServer(boolean)} instead
   */
  @Deprecated
  public TestWorkflowService(boolean lockTimeSkipping) {
    this(0, true);
    if (lockTimeSkipping) {
      this.lockTimeSkipping("constructor");
    }
  }

  /**
   * Creates an instance of TestWorkflowService that does not manage its own gRPC server. Useful for
   * including in an externally managed gRPC server.
   *
   * @deprecated use {@link TestServicesStarter} to create just the services with gRPC server
   */
  @Deprecated
  public static TestWorkflowService createWithNoGrpcServer() {
    return new TestWorkflowService(0, false);
  }

  private TestWorkflowService(long initialTimeMillis, boolean startInProcessServer) {
    this.selfAdvancingTimer =
        new SelfAdvancingTimerImpl(initialTimeMillis, Clock.systemDefaultZone());
    store = new TestWorkflowStoreImpl(this.selfAdvancingTimer);
    visibilityStore = new TestVisibilityStoreImpl();
    nexusEndpointStore = new TestNexusEndpointStoreImpl();
    outOfProcessServer = null;
    if (startInProcessServer) {
      this.inProcessServer = new InProcessGRPCServer(Collections.singletonList(this));
      this.workflowServiceStubs =
          WorkflowServiceStubs.newServiceStubs(
              WorkflowServiceStubsOptions.newBuilder()
                  .setChannel(inProcessServer.getChannel())
                  .build());
    } else {
      this.inProcessServer = null;
      this.workflowServiceStubs = null;
    }
  }

  /**
   * Creates an out-of-process rather than in-process server, and does not set up a client. Useful,
   * for example, if you want to use the test service from other SDKs.
   *
   * @param port the port to listen on
   * @deprecated use {@link TestServer#createPortBoundServer(int, boolean)} instead and pass {@code
   *     lockTimeSkipping=false} to emulate the behavior of this method
   */
  @Deprecated
  public static TestWorkflowService createServerOnly(int port) {
    TestWorkflowService result = new TestWorkflowService(true, port);
    log.info("Server started, listening on " + port);
    return result;
  }

  private TestWorkflowService(boolean isOutOfProc, int port) {
    // isOutOfProc is just here to make unambiguous constructor overloading.
    Preconditions.checkState(isOutOfProc, "Impossible.");
    inProcessServer = null;
    workflowServiceStubs = null;
    this.selfAdvancingTimer = new SelfAdvancingTimerImpl(0, Clock.systemDefaultZone());
    store = new TestWorkflowStoreImpl(selfAdvancingTimer);
    visibilityStore = new TestVisibilityStoreImpl();
    nexusEndpointStore = new TestNexusEndpointStoreImpl();
    try {
      ServerBuilder<?> serverBuilder =
          Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
      GRPCServerHelper.registerServicesAndHealthChecks(
          Collections.singletonList(this), serverBuilder);
      outOfProcessServer = serverBuilder.build().start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Deprecated
  public WorkflowServiceStubs newClientStub() {
    if (workflowServiceStubs == null) {
      throw new RuntimeException(
          "Cannot get a client when you created your TestWorkflowService with createServerOnly.");
    }
    return workflowServiceStubs;
  }

  private static StatusRuntimeException createInvalidArgument(String description) {
    throw Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
  }
}
