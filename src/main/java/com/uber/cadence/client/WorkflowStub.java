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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowExecution;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WorkflowStub is a client side stub to a single workflow instance. It can be used to start,
 * signal, query, wait for completion and cancel a workflow execution. Created through {@link
 * WorkflowClient#newUntypedWorkflowStub(String, WorkflowOptions)} or {@link
 * WorkflowClient#newUntypedWorkflowStub(WorkflowExecution, Optional)}.
 */
public interface WorkflowStub {

  void signal(String signalName, Object... args);

  WorkflowExecution start(Object... args);

  Optional<String> getWorkflowType();

  WorkflowExecution getExecution();

  /**
   * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
   * call performs long poll on Cadence service waiting for workflow completion notification.
   *
   * @param returnType class of the workflow return value
   * @param <R> type of the workflow return value
   * @return workflow return value
   */
  <R> R getResult(Class<R> returnType);

  <R> CompletableFuture<R> getResultAsync(Class<R> returnType);

  /**
   * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
   * call performs long poll on Cadence service waiting for workflow completion notification.
   *
   * @param timeout maximum time to wait
   * @param unit unit of timeout
   * @param returnType class of the workflow return value
   * @param <R> type of the workflow return value
   * @return workflow return value
   * @throws TimeoutException if workflow is not completed after the timeout time.
   */
  <R> R getResult(long timeout, TimeUnit unit, Class<R> returnType) throws TimeoutException;

  <R> CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit, Class<R> returnType);

  <R> R query(String queryType, Class<R> returnType, Object... args);

  /** Request cancellation. */
  void cancel();

  Optional<WorkflowOptions> getOptions();
}
