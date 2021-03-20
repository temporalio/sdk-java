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

package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Experimental
public interface WorkflowClientCallsInterceptor {
  /**
   *
   * @see #signalWithStart if you implement this method, {@link #signalWithStart} most likely needs to be implemented too
   */
  WorkflowStartOutput start(WorkflowStartInput input);

  /**
   *
   * @see #signalWithStart if you implement this method, {@link #signalWithStart} most likely needs to be implemented too
   */
  WorkflowSignalOutput signal(WorkflowSignalInput input);

  WorkflowStartOutput signalWithStart(WorkflowStartWithSignalInput input);

  /**
   *
   * @see #getResultAsync if you implement this method, {@link #getResultAsync} most likely needs to be implemented too
   */
  <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException;

  /**
   *
   * @see #getResult if you implement this method, {@link #getResult} most likely needs to be implemented too
   */
  <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input);

  <R> QueryOutput<R> query(QueryInput<R> input);

  CancelOutput cancel(CancelInput input);

  TerminateOutput terminate(TerminateInput input);

  final class WorkflowStartInput {
    private final String workflowId;
    private final String workflowType;
    private final Header header;
    private final Object[] arguments;
    private final WorkflowOptions options;

    public WorkflowStartInput(
        String workflowId,
        String workflowType,
        Header header,
        Object[] arguments,
        WorkflowOptions options) {
      this.workflowId = workflowId;
      this.workflowType = workflowType;
      this.header = header;
      this.arguments = arguments;
      this.options = options;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getWorkflowType() {
      return workflowType;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public WorkflowOptions getOptions() {
      return options;
    }
  }

  final class WorkflowSignalInput {
    private final String workflowId;
    private final String signalName;
    private final Object[] arguments;

    public WorkflowSignalInput(String workflowId, String signalName, Object[] signalArguments) {
      this.workflowId = workflowId;
      this.signalName = signalName;
      this.arguments = signalArguments;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class WorkflowSignalOutput {}

  final class WorkflowStartWithSignalInput {
    private final WorkflowStartInput workflowStartInput;
    private final String signalName;
    private final Object[] signalArguments;

    public WorkflowStartWithSignalInput(
        WorkflowStartInput workflowStartInput, String signalName, Object[] signalArguments) {
      this.workflowStartInput = workflowStartInput;
      this.signalName = signalName;
      this.signalArguments = signalArguments;
    }

    public WorkflowStartInput getWorkflowStartInput() {
      return workflowStartInput;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getSignalArguments() {
      return signalArguments;
    }
  }

  final class WorkflowStartOutput {
    private final WorkflowExecution workflowExecution;

    public WorkflowStartOutput(WorkflowExecution workflowExecution) {
      this.workflowExecution = workflowExecution;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }
  }

  final class GetResultInput<R> {
    private final WorkflowExecution workflowExecution;
    private final Optional<String> workflowType;
    private final long timeout;
    private final TimeUnit timeoutUnit;
    private final Class<R> resultClass;
    private final Type resultType;

    public GetResultInput(
        WorkflowExecution workflowExecution,
        Optional<String> workflowType,
        long timeout,
        TimeUnit timeoutUnit,
        Class<R> resultClass,
        Type resultType) {
      this.workflowExecution = workflowExecution;
      this.workflowType = workflowType;
      this.timeout = timeout;
      this.timeoutUnit = timeoutUnit;
      this.resultClass = resultClass;
      this.resultType = resultType;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public Optional<String> getWorkflowType() {
      return workflowType;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getTimeoutUnit() {
      return timeoutUnit;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }
  }

  final class GetResultOutput<R> {
    private final R result;

    public GetResultOutput(R result) {
      this.result = result;
    }

    public R getResult() {
      return result;
    }
  }

  final class GetResultAsyncOutput<R> {
    private final CompletableFuture<R> result;

    public GetResultAsyncOutput(CompletableFuture<R> result) {
      this.result = result;
    }

    public CompletableFuture<R> getResult() {
      return result;
    }
  }

  final class QueryInput<R> {
    private final WorkflowExecution workflowExecution;
    private final String queryType;
    private final Object[] arguments;
    private final Class<R> resultClass;
    private final Type resultType;

    public QueryInput(
        WorkflowExecution workflowExecution,
        String queryType,
        Object[] arguments,
        Class<R> resultClass,
        Type resultType) {
      this.workflowExecution = workflowExecution;
      this.queryType = queryType;
      this.arguments = arguments;
      this.resultClass = resultClass;
      this.resultType = resultType;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public String getQueryType() {
      return queryType;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }
  }

  final class QueryOutput<R> {
    private final WorkflowExecutionStatus queryRejectedStatus;
    private final R result;

    /**
     * @param queryRejectedStatus should be null if query is not rejected
     * @param result converted result value
     */
    public QueryOutput(WorkflowExecutionStatus queryRejectedStatus, R result) {
      this.queryRejectedStatus = queryRejectedStatus;
      this.result = result;
    }

    public boolean isQueryRejected() {
      return queryRejectedStatus != null;
    }

    public WorkflowExecutionStatus getQueryRejectedStatus() {
      return queryRejectedStatus;
    }

    public R getResult() {
      return result;
    }
  }

  final class CancelInput {
    private final WorkflowExecution workflowExecution;

    public CancelInput(WorkflowExecution workflowExecution) {
      this.workflowExecution = workflowExecution;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }
  }

  final class CancelOutput {}

  final class TerminateInput {
    private final WorkflowExecution workflowExecution;
    private final String reason;
    private final Object[] details;

    public TerminateInput(WorkflowExecution workflowExecution, String reason, Object[] details) {
      this.workflowExecution = workflowExecution;
      this.reason = reason;
      this.details = details;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public String getReason() {
      return reason;
    }

    public Object[] getDetails() {
      return details;
    }
  }

  final class TerminateOutput {}
}
