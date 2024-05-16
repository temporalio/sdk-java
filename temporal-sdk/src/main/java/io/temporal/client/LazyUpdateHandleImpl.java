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

package io.temporal.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Experimental
final class LazyUpdateHandleImpl<T> implements UpdateHandle<T> {

  private final WorkflowClientCallsInterceptor workflowClientInvoker;
  private final String workflowType;
  private final String updateName;
  private final String id;
  private final WorkflowExecution execution;
  private final Class<T> resultClass;
  private final Type resultType;

  LazyUpdateHandleImpl(
      WorkflowClientCallsInterceptor workflowClientInvoker,
      String workflowType,
      String updateName,
      String id,
      WorkflowExecution execution,
      Class<T> resultClass,
      Type resultType) {
    this.workflowClientInvoker = workflowClientInvoker;
    this.workflowType = workflowType;
    this.updateName = updateName;
    this.id = id;
    this.execution = execution;
    this.resultClass = resultClass;
    this.resultType = resultType;
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit) {
    WorkflowClientCallsInterceptor.PollWorkflowUpdateOutput<T> output =
        workflowClientInvoker.pollWorkflowUpdate(
            new WorkflowClientCallsInterceptor.PollWorkflowUpdateInput<>(
                execution, updateName, id, resultClass, resultType, timeout, unit));

    return output
        .getResult()
        .exceptionally(
            failure -> {
              if (failure instanceof CompletionException) {
                // unwrap the CompletionException
                failure = ((Throwable) failure).getCause();
              }
              failure = CheckedExceptionWrapper.unwrap((Throwable) failure);
              if (failure instanceof Error) {
                throw (Error) failure;
              }
              if (failure instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) failure;
                if (Status.Code.NOT_FOUND.equals(sre.getStatus().getCode())) {
                  // Currently no way to tell if the NOT_FOUND was because the workflow ID
                  // does not exist or because the update ID does not exist.
                  throw sre;
                }
              } else if (failure instanceof WorkflowException) {
                throw (WorkflowException) failure;
              } else if (failure instanceof TimeoutException) {
                throw new CompletionException((TimeoutException) failure);
              }
              throw new WorkflowServiceException(execution, workflowType, (Throwable) failure);
            });
  }

  @Override
  public CompletableFuture<T> getResultAsync() {
    return this.getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
  }
}
