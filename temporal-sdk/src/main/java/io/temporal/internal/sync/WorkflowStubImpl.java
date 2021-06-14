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

import com.google.common.base.Strings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.errordetails.v1.QueryFailedFailure;
import io.temporal.api.errordetails.v1.WorkflowExecutionAlreadyStartedFailure;
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
import io.temporal.internal.common.WorkflowExecutionFailedException;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.serviceclient.StatusUtils;
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
  private final Optional<String> workflowType;
  private final AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
  private final Optional<WorkflowOptions> options;

  WorkflowStubImpl(
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientInvoker,
      Optional<String> workflowType,
      WorkflowExecution execution) {
    this.clientOptions = clientOptions;
    this.workflowClientInvoker = workflowClientInvoker;
    this.workflowType = workflowType;
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
      String workflowType,
      WorkflowOptions options) {
    this.clientOptions = clientOptions;
    this.workflowClientInvoker = workflowClientInvoker;
    this.workflowType = Optional.of(workflowType);
    this.options = Optional.of(options);
  }

  @Override
  public void signal(String signalName, Object... args) {
    checkStarted();
    try {
      workflowClientInvoker.signal(
          new WorkflowClientCallsInterceptor.WorkflowSignalInput(
              currentExecutionWithoutRunId(), signalName, args));
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
    WorkflowExecution workflowExecution = null;
    try {
      WorkflowClientCallsInterceptor.WorkflowStartOutput workflowStartOutput =
          workflowClientInvoker.start(
              new WorkflowClientCallsInterceptor.WorkflowStartInput(
                  workflowId, workflowType.get(), Header.empty(), args, options));
      workflowExecution = workflowStartOutput.getWorkflowExecution();
      execution.set(workflowExecution);
      return workflowExecution;
    } catch (StatusRuntimeException e) {
      throw wrapStartException(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      if (workflowExecution == null) {
        // if start failed with exception - there could be no valid workflow execution populated
        // from the server.
        // WorkflowServiceException requires not null workflowExecution, so we have to provide
        // an WorkflowExecution instance with just a workflowId
        workflowExecution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
      }
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
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
    WorkflowExecution workflowExecution = null;
    try {
      WorkflowClientCallsInterceptor.WorkflowSignalWithStartOutput workflowStartOutput =
          workflowClientInvoker.signalWithStart(
              new WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput(
                  new WorkflowClientCallsInterceptor.WorkflowStartInput(
                      workflowId, workflowType.get(), Header.empty(), startArgs, options),
                  signalName,
                  signalArgs));
      workflowExecution = workflowStartOutput.getWorkflowStartOutput().getWorkflowExecution();
      execution.set(workflowExecution);
      return workflowExecution;
    } catch (StatusRuntimeException e) {
      throw wrapStartException(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      if (workflowExecution == null) {
        // if start failed with exception - there could be no valid workflow execution populated
        // from the server.
        // WorkflowServiceException requires not null workflowExecution, so we have to provide
        // an WorkflowExecution instance with just a workflowId
        workflowExecution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
      }
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
    }
  }

  private RuntimeException wrapStartException(
      String workflowId, String workflowType, StatusRuntimeException e) {
    WorkflowExecution.Builder executionBuilder =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId);

    WorkflowExecutionAlreadyStartedFailure f =
        StatusUtils.getFailure(e, WorkflowExecutionAlreadyStartedFailure.class);
    if (f != null) {
      WorkflowExecution exe = executionBuilder.setRunId(f.getRunId()).build();
      execution.set(exe);
      return new WorkflowExecutionAlreadyStarted(exe, workflowType, e);
    } else {
      WorkflowExecution exe = executionBuilder.build();
      return new WorkflowServiceException(exe, workflowType, e);
    }
  }

  private static String getWorkflowIdForStart(WorkflowOptions options) {
    String workflowId = options.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    return workflowId;
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
      WorkflowClientCallsInterceptor.GetResultOutput<R> result =
          workflowClientInvoker.getResult(
              new WorkflowClientCallsInterceptor.GetResultInput<>(
                  execution.get(), workflowType, timeout, unit, resultClass, resultType));
      return result.getResult();
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
    WorkflowClientCallsInterceptor.GetResultAsyncOutput<R> result =
        workflowClientInvoker.getResultAsync(
            new WorkflowClientCallsInterceptor.GetResultInput<>(
                execution.get(), workflowType, timeout, unit, resultClass, resultType));
    return result
        .getResult()
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
              return r;
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
    WorkflowClientCallsInterceptor.QueryOutput<R> result;
    WorkflowExecution workflowExecution = execution.get();
    try {
      result =
          workflowClientInvoker.query(
              new WorkflowClientCallsInterceptor.QueryInput<>(
                  workflowExecution, queryType, args, resultClass, resultType));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new WorkflowNotFoundException(workflowExecution, workflowType.orElse(null));
      } else if (StatusUtils.hasFailure(e, QueryFailedFailure.class)) {
        throw new WorkflowQueryException(workflowExecution, workflowType.orElse(null), e);
      }
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
    } catch (Exception e) {
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
    }
    if (result.isQueryRejected()) {
      throw new WorkflowQueryRejectedException(
          workflowExecution,
          workflowType.orElse(null),
          clientOptions.getQueryRejectCondition(),
          result.getQueryRejectedStatus(),
          null);
    }
    return result.getResult();
  }

  @Override
  public void cancel() {
    checkStarted();
    workflowClientInvoker.cancel(
        new WorkflowClientCallsInterceptor.CancelInput(currentExecutionWithoutRunId()));
  }

  @Override
  public void terminate(String reason, Object... details) {
    checkStarted();
    workflowClientInvoker.terminate(
        new WorkflowClientCallsInterceptor.TerminateInput(
            currentExecutionWithoutRunId(), reason, details));
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

  private void checkExecutionIsNotStarted() {
    if (execution.get() != null) {
      throw new IllegalStateException(
          "Cannot reuse a stub instance to start more than one workflow execution. The stub "
              + "points to already started execution. If you are trying to wait for a workflow completion either "
              + "change WorkflowIdReusePolicy from AllowDuplicate or use WorkflowStub.getResult");
    }
  }

  /**
   * RunId can change e.g. workflow does ContinueAsNew. Emptying runId in workflowExecution allows
   * Temporal server figure out the current run id dynamically.
   */
  private WorkflowExecution currentExecutionWithoutRunId() {
    WorkflowExecution workflowExecution = execution.get();
    if (Strings.isNullOrEmpty(workflowExecution.getRunId())) {
      return workflowExecution;
    } else {
      return WorkflowExecution.newBuilder(workflowExecution).setRunId("").build();
    }
  }
}
