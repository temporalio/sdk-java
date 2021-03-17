/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.errordetails.v1.QueryFailedFailure;
import io.temporal.api.errordetails.v1.WorkflowExecutionAlreadyStartedFailure;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowQueryException;
import io.temporal.client.WorkflowQueryRejectedException;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.common.StatusUtils;
import io.temporal.internal.common.WorkflowExecutionFailedException;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.external.GenericWorkflowClientExternal;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class WorkflowStubImpl implements WorkflowStub {
  private final WorkflowClientOptions clientOptions;
  private final WorkflowClientCallsInterceptor workflowClientInvoker;
  // TODO most direct usage of genericClient should be refactored and workflowClientInvoker methods
  // should be used instead
  private final GenericWorkflowClientExternal genericClient;
  private final Optional<String> workflowType;
  private final Scope metricsScope;
  private final AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
  private final Optional<WorkflowOptions> options;

  WorkflowStubImpl(
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientInvoker,
      GenericWorkflowClientExternal genericClient,
      Optional<String> workflowType,
      WorkflowExecution execution,
      Scope metricsScope) {
    this.clientOptions = clientOptions;
    this.workflowClientInvoker = workflowClientInvoker;
    this.genericClient = genericClient;
    this.workflowType = workflowType;
    this.metricsScope = metricsScope;
    if (execution == null
        || execution.getWorkflowId() == null
        || execution.getWorkflowId().isEmpty()) {
      throw new IllegalArgumentException("null or empty workflowId");
    }
    this.execution.set(execution);
    this.options = Optional.empty();
  }

  WorkflowStubImpl(
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientInvoker,
      GenericWorkflowClientExternal genericClient,
      String workflowType,
      WorkflowOptions options,
      Scope metricsScope) {
    this.clientOptions = clientOptions;
    this.workflowClientInvoker = workflowClientInvoker;
    this.genericClient = genericClient;
    this.workflowType = Optional.of(workflowType);
    this.metricsScope = metricsScope;
    this.options = Optional.of(options);
  }

  @Override
  public void signal(String signalName, Object... args) {
    checkStarted();
    SignalWorkflowExecutionRequest.Builder request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setSignalName(signalName)
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(execution.get().getWorkflowId()));

    if (clientOptions.getIdentity() != null) {
      request.setIdentity(clientOptions.getIdentity());
    }
    if (clientOptions.getNamespace() != null) {
      request.setNamespace(clientOptions.getNamespace());
    }
    Optional<Payloads> input = clientOptions.getDataConverter().toPayloads(args);
    if (input.isPresent()) {
      request.setInput(input.get());
    }
    try {
      genericClient.signal(request.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new WorkflowNotFoundException(execution.get(), workflowType.orElse(null));
      } else {
        throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
      }
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
  }

  private WorkflowExecution startWithOptions(WorkflowOptions options, Object... args) {
    checkExecutionIsNotStarted();
    String workflowId = getWorkflowIdForStart(options);
    try {
      WorkflowClientCallsInterceptor.WorkflowStartOutput workflowStartOutput =
          workflowClientInvoker.start(
              new WorkflowClientCallsInterceptor.WorkflowStartInput(
                  workflowId, workflowType.get(), Header.empty(), args, options));
      WorkflowExecution workflowExecution = workflowStartOutput.getWorkflowExecution();
      execution.set(workflowExecution);
      return workflowExecution;
    } catch (StatusRuntimeException e) {
      throw wrapExecutionAlreadyStarted(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
  }

  @Override
  public WorkflowExecution start(Object... args) {
    if (!options.isPresent()) {
      throw new IllegalStateException("Required parameter WorkflowOptions is missing");
    }
    return startWithOptions(WorkflowOptions.merge(null, null, options.get()), args);
  }

  private WorkflowExecution signalWithStartWithOptions(
      WorkflowOptions options, String signalName, Object[] signalArgs, Object[] startArgs) {
    checkExecutionIsNotStarted();
    String workflowId = getWorkflowIdForStart(options);
    try {
      WorkflowClientCallsInterceptor.WorkflowStartOutput workflowStartOutput =
          workflowClientInvoker.signalWithStart(
              new WorkflowClientCallsInterceptor.WorkflowStartWithSignalInput(
                  new WorkflowClientCallsInterceptor.WorkflowStartInput(
                      workflowId, workflowType.get(), Header.empty(), startArgs, options),
                  signalName,
                  signalArgs));
      WorkflowExecution workflowExecution = workflowStartOutput.getWorkflowExecution();
      execution.set(workflowExecution);
      return workflowExecution;
    } catch (StatusRuntimeException e) {
      throw wrapExecutionAlreadyStarted(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
  }

  private RuntimeException wrapExecutionAlreadyStarted(
      String workflowId, String workflowType, StatusRuntimeException e) {
    WorkflowExecutionAlreadyStartedFailure f =
        StatusUtils.getFailure(e, WorkflowExecutionAlreadyStartedFailure.class);
    if (f != null) {
      WorkflowExecution exe =
          WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(f.getRunId()).build();
      execution.set(exe);
      return new WorkflowExecutionAlreadyStarted(exe, workflowType, e);
    } else {
      return e;
    }
  }

  private static String getWorkflowIdForStart(WorkflowOptions options) {
    String workflowId = options.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    return workflowId;
  }

  private void checkExecutionIsNotStarted() {
    if (execution.get() != null) {
      throw new IllegalStateException(
          "Cannot reuse a stub instance to start more than one workflow execution. The stub "
              + "points to already started execution. If you are trying to wait for a workflow completion either "
              + "change WorkflowIdReusePolicy from AllowDuplicate or use WorkflowStub.getResult");
    }
  }

  @Override
  public WorkflowExecution signalWithStart(
      String signalName, Object[] signalArgs, Object[] startArgs) {
    if (!options.isPresent()) {
      throw new IllegalStateException("Required parameter WorkflowOptions is missing");
    }
    return signalWithStartWithOptions(
        WorkflowOptions.merge(null, null, options.get()), signalName, signalArgs, startArgs);
  }

  @Override
  public Optional<String> getWorkflowType() {
    return workflowType;
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution.get();
  }

  @Override
  public <R> R getResult(Class<R> resultClass) {
    return getResult(resultClass, resultClass);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, Type resultType) {
    try {
      // int max to not overflow long
      return getResult(Integer.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
    } catch (TimeoutException e) {
      throw CheckedExceptionWrapper.wrap(e);
    }
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
      throws TimeoutException {
    return getResult(timeout, unit, resultClass, resultClass);
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType)
      throws TimeoutException {
    checkStarted();
    try {
      Optional<Payloads> resultValue =
          WorkflowExecutionUtils.getWorkflowExecutionResult(
              genericClient.getService(),
              genericClient.getNamespace(),
              execution.get(),
              workflowType,
              metricsScope,
              clientOptions.getDataConverter(),
              timeout,
              unit);
      return clientOptions.getDataConverter().fromPayloads(0, resultValue, resultClass, resultType);
    } catch (TimeoutException e) {
      throw e;
    } catch (Exception e) {
      return mapToWorkflowFailureException(e, resultClass);
    }
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
    return getResultAsync(resultClass, resultClass);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, Type resultType) {
    return getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass) {
    return getResultAsync(timeout, unit, resultClass, resultClass);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, Type resultType) {
    checkStarted();
    return WorkflowExecutionUtils.getWorkflowExecutionResultAsync(
            genericClient.getService(),
            genericClient.getNamespace(),
            execution.get(),
            workflowType,
            timeout,
            unit,
            clientOptions.getDataConverter())
        .handle(
            (r, e) -> {
              if (e instanceof CompletionException) {
                e = e.getCause();
              }
              if (e instanceof WorkflowExecutionFailedException) {
                return mapToWorkflowFailureException(
                    (WorkflowExecutionFailedException) e, resultClass);
              }
              if (e != null) {
                throw CheckedExceptionWrapper.wrap(e);
              }
              if (r == null) {
                return null;
              }
              return clientOptions.getDataConverter().fromPayloads(0, r, resultClass, resultType);
            });
  }

  private <R> R mapToWorkflowFailureException(
      Exception failure, @SuppressWarnings("unused") Class<R> returnType) {
    Throwable f = CheckedExceptionWrapper.unwrap(failure);
    if (f instanceof Error) {
      throw (Error) f;
    }
    failure = (Exception) f;
    if (failure instanceof WorkflowExecutionFailedException) {
      WorkflowExecutionFailedException executionFailed = (WorkflowExecutionFailedException) failure;
      Throwable cause =
          FailureConverter.failureToException(
              executionFailed.getFailure(), clientOptions.getDataConverter());
      throw new WorkflowFailedException(
          execution.get(),
          workflowType.orElse(null),
          executionFailed.getWorkflowTaskCompletedEventId(),
          executionFailed.getRetryState(),
          cause);
    } else if (failure instanceof StatusRuntimeException) {
      StatusRuntimeException sre = (StatusRuntimeException) failure;
      if (sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new WorkflowNotFoundException(execution.get(), workflowType.orElse(null));
      } else {
        throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), failure);
      }
    } else if (failure instanceof CanceledFailure) {
      throw (CanceledFailure) failure;
    } else if (failure instanceof WorkflowException) {
      throw (WorkflowException) failure;
    } else {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), failure);
    }
  }

  @Override
  public <R> R query(String queryType, Class<R> resultClass, Object... args) {
    return query(queryType, resultClass, resultClass, args);
  }

  @Override
  public <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args) {
    checkStarted();
    WorkflowQuery.Builder query = WorkflowQuery.newBuilder().setQueryType(queryType);
    Optional<Payloads> input = clientOptions.getDataConverter().toPayloads(args);
    if (input.isPresent()) {
      query.setQueryArgs(input.get());
    }
    QueryWorkflowRequest request =
        QueryWorkflowRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(execution.get().getWorkflowId())
                    .setRunId(execution.get().getRunId()))
            .setQuery(query)
            .setQueryRejectCondition(clientOptions.getQueryRejectCondition())
            .build();

    QueryWorkflowResponse result;
    try {
      result = genericClient.query(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new WorkflowNotFoundException(execution.get(), workflowType.orElse(null));
      } else if (StatusUtils.hasFailure(e, QueryFailedFailure.class)) {
        throw new WorkflowQueryException(execution.get(), workflowType.orElse(null), e);
      }
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
    if (!result.hasQueryRejected()) {
      Optional<Payloads> queryResult =
          result.hasQueryResult() ? Optional.of(result.getQueryResult()) : Optional.empty();
      return clientOptions.getDataConverter().fromPayloads(0, queryResult, resultClass, resultType);
    } else {
      throw new WorkflowQueryRejectedException(
          execution.get(),
          workflowType.orElse(null),
          clientOptions.getQueryRejectCondition(),
          result.getQueryRejected().getStatus(),
          null);
    }
  }

  @Override
  public void cancel() {
    if (execution.get() == null || execution.get().getWorkflowId() == null) {
      throw new IllegalStateException("Not started");
    }

    // RunId can change if workflow does ContinueAsNew. So we do not set it here and
    // let the server figure out the current run.
    RequestCancelWorkflowExecutionRequest.Builder request =
        RequestCancelWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(execution.get().getWorkflowId()))
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity());
    genericClient.requestCancel(request.build());
  }

  @Override
  public void terminate(String reason, Object... details) {
    if (execution.get() == null || execution.get().getWorkflowId() == null) {
      throw new IllegalStateException("Not started");
    }

    // RunId can change if workflow does ContinueAsNew. So we do not set it here and
    // let the server figure out the current run.
    TerminateWorkflowExecutionRequest.Builder request =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder().setWorkflowId(execution.get().getWorkflowId()))
            .setReason(reason);
    Optional<Payloads> payloads = clientOptions.getDataConverter().toPayloads(details);
    if (payloads.isPresent()) {
      request.setDetails(payloads.get());
    }
    genericClient.terminate(request.build());
  }

  @Override
  public Optional<WorkflowOptions> getOptions() {
    return options;
  }

  private void checkStarted() {
    if (execution.get() == null || execution.get().getWorkflowId() == null) {
      throw new IllegalStateException("Null workflowId. Was workflow started?");
    }
  }
}
