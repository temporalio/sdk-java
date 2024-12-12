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

package io.temporal.internal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class CompletedWorkflowUpdateHandleImpl<T> implements WorkflowUpdateHandle<T> {

  private final String id;
  private final WorkflowExecution execution;
  private final WorkflowUpdateException exception;
  private final T result;

  public CompletedWorkflowUpdateHandleImpl(String id, WorkflowExecution execution, T result) {
    this.id = id;
    this.execution = execution;
    this.result = result;
    this.exception = null;
  }

  public CompletedWorkflowUpdateHandleImpl(
      String id, WorkflowExecution execution, WorkflowUpdateException ex) {
    this.id = id;
    this.execution = execution;
    this.exception = ex;
    this.result = null;
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
  public T getResult() {
    if (exception != null) {
      throw exception;
    }
    return result;
  }

  @Override
  public T getResult(long timeout, TimeUnit unit) {
    return getResult();
  }

  @Override
  public CompletableFuture<T> getResultAsync() {
    if (exception != null) {
      CompletableFuture<T> result = new CompletableFuture<>();
      result.completeExceptionally(exception);
      return result;
    }
    return CompletableFuture.completedFuture(result);
  }

  @Override
  public CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit) {
    return getResultAsync();
  }
}
