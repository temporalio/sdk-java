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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.StartWorkflowExecutionParameters;
import io.temporal.internal.common.StatusUtils;
import io.temporal.internal.common.WorkflowExecutionFailedException;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.external.GenericWorkflowClientExternal;
import io.temporal.internal.replay.QueryWorkflowParameters;
import io.temporal.internal.replay.SignalExternalWorkflowParameters;
import io.temporal.proto.common.Payload;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.errordetails.QueryFailedFailure;
import io.temporal.proto.errordetails.WorkflowExecutionAlreadyStartedFailure;
import io.temporal.proto.query.QueryConsistencyLevel;
import io.temporal.proto.workflowservice.QueryWorkflowResponse;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class WorkflowStubImpl implements WorkflowStub {

  private final GenericWorkflowClientExternal genericClient;
  private final Optional<String> workflowType;
  private AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
  private final Optional<WorkflowOptions> options;
  private final WorkflowClientOptions clientOptions;

  WorkflowStubImpl(
      WorkflowClientOptions clientOptions,
      GenericWorkflowClientExternal genericClient,
      Optional<String> workflowType,
      WorkflowExecution execution) {
    this.clientOptions = clientOptions;
    this.genericClient = genericClient;
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
      GenericWorkflowClientExternal genericClient,
      String workflowType,
      WorkflowOptions options) {
    this.clientOptions = clientOptions;
    this.genericClient = genericClient;
    this.workflowType = Optional.of(workflowType);
    this.options = Optional.of(options);
  }

  @Override
  public void signal(String signalName, Object... input) {
    checkStarted();
    SignalExternalWorkflowParameters p = new SignalExternalWorkflowParameters();
    p.setInput(clientOptions.getDataConverter().toPayloads(input));
    p.setSignalName(signalName);
    p.setWorkflowId(execution.get().getWorkflowId());
    // TODO: Deal with signaling started workflow only, when requested
    // Commented out to support signaling workflows that called continue as new.
    //        p.setRunId(execution.getRunId());
    try {
      genericClient.signalWorkflowExecution(p);
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

  private WorkflowExecution startWithOptions(WorkflowOptions o, Object... args) {
    StartWorkflowExecutionParameters p = getStartWorkflowExecutionParameters(o, args);
    try {
      execution.set(genericClient.startWorkflow(p));
    } catch (StatusRuntimeException e) {
      WorkflowExecutionAlreadyStartedFailure f =
          StatusUtils.getFailure(e, WorkflowExecutionAlreadyStartedFailure.class);
      if (f != null) {
        WorkflowExecution exe =
            WorkflowExecution.newBuilder()
                .setWorkflowId(p.getWorkflowId())
                .setRunId(f.getRunId())
                .build();
        execution.set(exe);
        throw new WorkflowExecutionAlreadyStarted(exe, workflowType.get(), e);
      } else {
        throw e;
      }
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
    return execution.get();
  }

  private StartWorkflowExecutionParameters getStartWorkflowExecutionParameters(
      WorkflowOptions o, Object[] args) {
    if (execution.get() != null) {
      throw new IllegalStateException(
          "Cannot reuse a stub instance to start more than one workflow execution. The stub "
              + "points to already started execution. If you are trying to wait for a workflow completion either "
              + "change WorkflowIdReusePolicy from AllowDuplicate or use WorkflowStub.getResult");
    }
    StartWorkflowExecutionParameters p = StartWorkflowExecutionParameters.fromWorkflowOptions(o);
    if (o.getWorkflowId() == null) {
      p.setWorkflowId(UUID.randomUUID().toString());
    } else {
      p.setWorkflowId(o.getWorkflowId());
    }
    p.setInput(clientOptions.getDataConverter().toPayloads(args));
    p.setWorkflowType(WorkflowType.newBuilder().setName(workflowType.get()).build());
    p.setMemo(convertMemoFromObjectToBytes(o.getMemo()));
    p.setSearchAttributes(convertSearchAttributesFromObjectToBytes(o.getSearchAttributes()));
    p.setContext(extractContextsAndConvertToBytes(o.getContextPropagators()));
    return p;
  }

  private Map<String, Payload> convertMapFromObjectToBytes(
      Map<String, Object> map, DataConverter dataConverter) {
    if (map == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (Map.Entry<String, Object> item : map.entrySet()) {
      try {
        result.put(item.getKey(), dataConverter.toPayload(item.getValue()).get());
      } catch (DataConverterException e) {
        throw new DataConverterException("Cannot serialize key " + item.getKey(), e.getCause());
      }
    }
    return result;
  }

  private Map<String, Payload> convertMemoFromObjectToBytes(Map<String, Object> map) {
    return convertMapFromObjectToBytes(map, clientOptions.getDataConverter());
  }

  private Map<String, Payload> convertSearchAttributesFromObjectToBytes(Map<String, Object> map) {
    return convertMapFromObjectToBytes(map, clientOptions.getDataConverter());
  }

  private Map<String, Payload> extractContextsAndConvertToBytes(
      List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return result;
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
    StartWorkflowExecutionParameters sp = getStartWorkflowExecutionParameters(options, startArgs);

    Optional<Payloads> signalInput = clientOptions.getDataConverter().toPayloads(signalArgs);
    SignalWithStartWorkflowExecutionParameters p =
        new SignalWithStartWorkflowExecutionParameters(sp, signalName, signalInput);
    try {
      execution.set(genericClient.signalWithStartWorkflowExecution(p));
    } catch (StatusRuntimeException e) {
      WorkflowExecutionAlreadyStartedFailure f =
          StatusUtils.getFailure(e, WorkflowExecutionAlreadyStartedFailure.class);
      if (f != null) {
        WorkflowExecution exe =
            WorkflowExecution.newBuilder()
                .setWorkflowId(sp.getWorkflowId())
                .setRunId(f.getRunId())
                .build();
        execution.set(exe);
        throw new WorkflowExecutionAlreadyStarted(exe, workflowType.get(), e);
      } else {
        throw e;
      }
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
    return execution.get();
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
              timeout,
              unit,
              clientOptions.getDataConverter());
      return clientOptions.getDataConverter().fromPayloads(resultValue, resultClass, resultType);
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
              return clientOptions.getDataConverter().fromPayloads(r, resultClass, resultType);
            });
  }

  private <R> R mapToWorkflowFailureException(
      Exception failure, @SuppressWarnings("unused") Class<R> returnType) {
    failure = CheckedExceptionWrapper.unwrap(failure);
    Class<Throwable> detailsClass;
    if (failure instanceof WorkflowExecutionFailedException) {
      WorkflowExecutionFailedException executionFailed = (WorkflowExecutionFailedException) failure;
      Throwable cause =
          FailureConverter.failureToException(
              executionFailed.getFailure(), clientOptions.getDataConverter());
      throw new WorkflowFailedException(
          execution.get(),
          workflowType.orElse(null),
          executionFailed.getDecisionTaskCompletedEventId(),
          executionFailed.getRetryStatus(),
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
    QueryWorkflowParameters p = new QueryWorkflowParameters();
    p.setInput(clientOptions.getDataConverter().toPayloads(args));
    p.setQueryType(queryType);
    p.setWorkflowId(execution.get().getWorkflowId());
    p.setQueryRejectCondition(clientOptions.getQueryRejectCondition());
    // Hardcode strong as Eventual should be deprecated.
    p.setQueryConsistencyLevel(QueryConsistencyLevel.Strong);
    QueryWorkflowResponse result;
    try {
      result = genericClient.queryWorkflow(p);
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
      return clientOptions.getDataConverter().fromPayloads(queryResult, resultClass, resultType);
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
      return;
    }

    // RunId can change if workflow does ContinueAsNew. So we do not set it here and
    // let the server figure out the current run.
    genericClient.requestCancelWorkflowExecution(
        WorkflowExecution.newBuilder().setWorkflowId(execution.get().getWorkflowId()).build());
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
