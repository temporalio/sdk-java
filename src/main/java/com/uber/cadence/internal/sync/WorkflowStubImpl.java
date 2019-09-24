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

import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.QueryFailedError;
import com.uber.cadence.QueryRejectCondition;
import com.uber.cadence.QueryWorkflowResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.DuplicateWorkflowException;
import com.uber.cadence.client.WorkflowException;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.client.WorkflowNotFoundException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowQueryException;
import com.uber.cadence.client.WorkflowServiceException;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.common.CheckedExceptionWrapper;
import com.uber.cadence.internal.common.QueryResponse;
import com.uber.cadence.internal.common.SignalWithStartWorkflowExecutionParameters;
import com.uber.cadence.internal.common.StartWorkflowExecutionParameters;
import com.uber.cadence.internal.common.WorkflowExecutionFailedException;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.external.GenericWorkflowClientExternal;
import com.uber.cadence.internal.replay.QueryWorkflowParameters;
import com.uber.cadence.internal.replay.SignalExternalWorkflowParameters;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class WorkflowStubImpl implements WorkflowStub {

  private final GenericWorkflowClientExternal genericClient;
  private final DataConverter dataConverter;
  private final Optional<String> workflowType;
  private AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
  private final Optional<WorkflowOptions> options;

  WorkflowStubImpl(
      GenericWorkflowClientExternal genericClient,
      DataConverter dataConverter,
      Optional<String> workflowType,
      WorkflowExecution execution) {
    this.genericClient = genericClient;
    this.dataConverter = dataConverter;
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
      GenericWorkflowClientExternal genericClient,
      DataConverter dataConverter,
      String workflowType,
      WorkflowOptions options) {
    this.genericClient = genericClient;
    this.dataConverter = dataConverter;
    this.workflowType = Optional.of(workflowType);
    this.options = Optional.of(options);
  }

  @Override
  public void signal(String signalName, Object... input) {
    checkStarted();
    SignalExternalWorkflowParameters p = new SignalExternalWorkflowParameters();
    p.setInput(dataConverter.toData(input));
    p.setSignalName(signalName);
    p.setWorkflowId(execution.get().getWorkflowId());
    // TODO: Deal with signaling started workflow only, when requested
    // Commented out to support signaling workflows that called continue as new.
    //        p.setRunId(execution.getRunId());
    try {
      genericClient.signalWorkflowExecution(p);
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType, e);
    }
  }

  private WorkflowExecution startWithOptions(WorkflowOptions o, Object... args) {
    StartWorkflowExecutionParameters p = getStartWorkflowExecutionParameters(o, args);
    try {
      execution.set(genericClient.startWorkflow(p));
    } catch (WorkflowExecutionAlreadyStartedError e) {
      execution.set(
          new WorkflowExecution().setWorkflowId(p.getWorkflowId()).setRunId(e.getRunId()));
      WorkflowExecution execution =
          new WorkflowExecution().setWorkflowId(p.getWorkflowId()).setRunId(e.getRunId());
      throw new DuplicateWorkflowException(execution, workflowType.get(), e.getMessage());
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType, e);
    }
    return execution.get();
  }

  private StartWorkflowExecutionParameters getStartWorkflowExecutionParameters(
      WorkflowOptions o, Object[] args) {
    if (execution.get() != null) {
      throw new DuplicateWorkflowException(
          execution.get(),
          workflowType.get(),
          "Cannot reuse a stub instance to start more than one workflow execution. The stub "
              + "points to already started execution.");
    }
    StartWorkflowExecutionParameters p = StartWorkflowExecutionParameters.fromWorkflowOptions(o);
    if (o.getWorkflowId() == null) {
      p.setWorkflowId(UUID.randomUUID().toString());
    } else {
      p.setWorkflowId(o.getWorkflowId());
    }
    p.setInput(dataConverter.toData(args));
    p.setWorkflowType(new WorkflowType().setName(workflowType.get()));
    p.setMemo(convertMemoFromObjectToBytes(o.getMemo()));
    p.setSearchAttributes(convertSearchAttributesFromObjectToBytes(o.getSearchAttributes()));
    return p;
  }

  private Map<String, byte[]> convertMapFromObjectToBytes(
      Map<String, Object> map, DataConverter dataConverter) {
    if (map == null) {
      return null;
    }
    Map<String, byte[]> result = new HashMap<>();
    for (Map.Entry<String, Object> item : map.entrySet()) {
      try {
        result.put(item.getKey(), dataConverter.toData(item.getValue()));
      } catch (DataConverterException e) {
        throw new DataConverterException("Cannot serialize key " + item.getKey(), e.getCause());
      }
    }
    return result;
  }

  private Map<String, byte[]> convertMemoFromObjectToBytes(Map<String, Object> map) {
    return convertMapFromObjectToBytes(map, dataConverter);
  }

  private Map<String, byte[]> convertSearchAttributesFromObjectToBytes(Map<String, Object> map) {
    return convertMapFromObjectToBytes(map, JsonDataConverter.getInstance());
  }

  @Override
  public WorkflowExecution start(Object... args) {
    if (!options.isPresent()) {
      throw new IllegalStateException("Required parameter WorkflowOptions is missing");
    }
    return startWithOptions(WorkflowOptions.merge(null, null, null, options.get()), args);
  }

  private WorkflowExecution signalWithStartWithOptions(
      WorkflowOptions options, String signalName, Object[] signalArgs, Object[] startArgs) {
    StartWorkflowExecutionParameters sp = getStartWorkflowExecutionParameters(options, startArgs);

    byte[] signalInput = dataConverter.toData(signalArgs);
    SignalWithStartWorkflowExecutionParameters p =
        new SignalWithStartWorkflowExecutionParameters(sp, signalName, signalInput);
    try {
      execution.set(genericClient.signalWithStartWorkflowExecution(p));
    } catch (Exception e) {
      throw new WorkflowServiceException(execution.get(), workflowType, e);
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
        WorkflowOptions.merge(null, null, null, options.get()), signalName, signalArgs, startArgs);
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
      return getResult(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
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
      byte[] resultValue =
          WorkflowExecutionUtils.getWorkflowExecutionResult(
              genericClient.getService(),
              genericClient.getDomain(),
              execution.get(),
              workflowType,
              timeout,
              unit);
      if (resultValue == null) {
        return null;
      }
      return dataConverter.fromData(resultValue, resultClass, resultType);
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
            genericClient.getDomain(),
            execution.get(),
            workflowType,
            timeout,
            unit)
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
              return dataConverter.fromData(r, resultClass, resultType);
            });
  }

  private <R> R mapToWorkflowFailureException(
      Exception failure, @SuppressWarnings("unused") Class<R> returnType) {
    failure = CheckedExceptionWrapper.unwrap(failure);
    Class<Throwable> detailsClass;
    if (failure instanceof WorkflowExecutionFailedException) {
      WorkflowExecutionFailedException executionFailed = (WorkflowExecutionFailedException) failure;
      try {
        @SuppressWarnings("unchecked")
        Class<Throwable> dc = (Class<Throwable>) Class.forName(executionFailed.getReason());
        detailsClass = dc;
      } catch (Exception e) {
        RuntimeException ee =
            new RuntimeException(
                "Couldn't deserialize failure cause "
                    + "as the reason field is expected to contain an exception class name",
                executionFailed);
        throw new WorkflowFailureException(
            execution.get(), workflowType, executionFailed.getDecisionTaskCompletedEventId(), ee);
      }
      Throwable cause =
          dataConverter.fromData(executionFailed.getDetails(), detailsClass, detailsClass);
      throw new WorkflowFailureException(
          execution.get(), workflowType, executionFailed.getDecisionTaskCompletedEventId(), cause);
    } else if (failure instanceof EntityNotExistsError) {
      throw new WorkflowNotFoundException(execution.get(), workflowType, failure.getMessage());
    } else if (failure instanceof CancellationException) {
      throw (CancellationException) failure;
    } else if (failure instanceof WorkflowException) {
      throw (WorkflowException) failure;
    } else {
      throw new WorkflowServiceException(execution.get(), workflowType, failure);
    }
  }

  @Override
  public <R> R query(String queryType, Class<R> resultClass, Object... args) {
    return query(queryType, resultClass, resultClass, args);
  }

  @Override
  public <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args) {
    return query(queryType, resultClass, resultType, null, args).getResult();
  }

  @Override
  public <R> QueryResponse<R> query(
      String queryType,
      Class<R> resultClass,
      QueryRejectCondition queryRejectCondition,
      Object... args) {
    return query(queryType, resultClass, resultClass, queryRejectCondition, args);
  }

  @Override
  public <R> QueryResponse<R> query(
      String queryType,
      Class<R> resultClass,
      Type resultType,
      QueryRejectCondition queryRejectCondition,
      Object... args) {
    checkStarted();
    QueryWorkflowParameters p = new QueryWorkflowParameters();
    p.setInput(dataConverter.toData(args));
    p.setQueryType(queryType);
    p.setWorkflowId(execution.get().getWorkflowId());
    p.setQueryRejectCondition(queryRejectCondition);
    try {
      QueryWorkflowResponse result = genericClient.queryWorkflow(p);
      if (result.queryRejected == null) {
        return new QueryResponse<>(
            null, dataConverter.fromData(result.getQueryResult(), resultClass, resultType));
      } else {
        return new QueryResponse<>(result.getQueryRejected(), null);
      }

    } catch (RuntimeException e) {
      Exception unwrapped = CheckedExceptionWrapper.unwrap(e);
      if (unwrapped instanceof EntityNotExistsError) {
        throw new WorkflowNotFoundException(execution.get(), workflowType, e.getMessage());
      }
      if (unwrapped instanceof QueryFailedError) {
        throw new WorkflowQueryException(execution.get(), unwrapped.getMessage());
      }
      if (unwrapped instanceof InternalServiceError) {
        throw new WorkflowServiceException(execution.get(), workflowType, unwrapped);
      }
      throw e;
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
        new WorkflowExecution().setWorkflowId(execution.get().getWorkflowId()));
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
