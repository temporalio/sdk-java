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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.common.Experimental;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TerminatedFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.sync.StubMarker;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * WorkflowStub is a client side stub to a single workflow instance. It can be used to start,
 * signal, query, update, wait for completion and cancel a workflow execution. Created through
 * {@link WorkflowClient#newUntypedWorkflowStub(String, WorkflowOptions)} or {@link
 * WorkflowClient#newUntypedWorkflowStub(WorkflowExecution, Optional)}.
 */
public interface WorkflowStub {

  /**
   * Extracts untyped WorkflowStub from a typed workflow stub created through {@link
   * WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   *
   * @param typed typed workflow stub
   * @param <T> type of the workflow stub interface
   * @return untyped workflow stub for the same workflow instance
   */
  static <T> WorkflowStub fromTyped(T typed) {
    if (!(typed instanceof StubMarker)) {
      throw new IllegalArgumentException(
          "arguments must be created through WorkflowClient.newWorkflowStub");
    }
    @SuppressWarnings("unchecked")
    StubMarker supplier = (StubMarker) typed;
    return (WorkflowStub) supplier.__getUntypedStub();
  }

  /**
   * Synchronously signals a workflow by invoking its signal handler. Usually a signal handler is a
   * method annotated with {@link io.temporal.workflow.SignalMethod}.
   *
   * @param signalName name of the signal handler. Usually it is a method name.
   * @param args signal method arguments
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist or completed and
   *     can't be signalled
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  void signal(String signalName, Object... args);

  /**
   * Synchronously update a workflow execution by invoking its update handler. Usually a update
   * handler is a method annotated with {@link io.temporal.workflow.UpdateMethod}.
   *
   * @param updateName name of the update handler. Usually it is a method name.
   * @param resultClass class of the update return value
   * @param <R> type of the update return value
   * @param args update method arguments
   * @return update result
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist or completed and
   *     can't be signalled
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  @Experimental
  <R> R update(String updateName, Class<R> resultClass, Object... args);

  /**
   * Asynchronously update a workflow execution by invoking its update handler and returning a
   * handle to the update request. Usually an update handler is a method annotated with {@link
   * io.temporal.workflow.UpdateMethod}.
   *
   * @param updateName name of the update handler. Usually it is a method name.
   * @param waitForStage stage to wait for before returning the update handle. Admitted is not
   *     allowed as a value.
   * @param resultClass class of the update return value
   * @param <R> type of the update return value
   * @param args update method arguments
   * @return update handle that can be used to get the result of the update.
   */
  @Experimental
  <R> WorkflowUpdateHandle<R> startUpdate(
      String updateName, WorkflowUpdateStage waitForStage, Class<R> resultClass, Object... args);

  /**
   * Asynchronously update a workflow execution by invoking its update handler and returning a
   * handle to the update request. If {@link WorkflowUpdateStage#COMPLETED} is specified, in the
   * options, the handle will not be returned until the update is completed.
   *
   * @param options options that will be used to configure and start a new update request.
   * @param args update method arguments
   * @return update handle that can be used to get the result of the update.
   */
  @Experimental
  <R> WorkflowUpdateHandle<R> startUpdate(UpdateOptions<R> options, Object... args);

  /**
   * Get an update handle to a previously started update request. Getting an update handle does not
   * guarantee the update ID exists.
   *
   * @param updateId the identifier for the requested update.
   * @param resultClass class of the update return value.
   * @param <R> type of the update return value.
   * @return update handle that can be used to get the result of the update.
   */
  @Experimental
  <R> WorkflowUpdateHandle<R> getUpdateHandle(String updateId, Class<R> resultClass);

  /**
   * Get an update handle to a previously started update request. Getting an update handle does not
   * guarantee the update ID exists.
   *
   * @param updateId is an application-layer identifier for the requested update. It must be unique
   *     within the scope of a workflow execution.
   * @param resultClass class of the update return value.
   * @param <R> type of the update return value.
   * @param resultType type of the update return value. Differs from resultClass for generic types.
   * @return update handle that can be used to get the result of the update.
   */
  @Experimental
  <R> WorkflowUpdateHandle<R> getUpdateHandle(
      String updateId, Class<R> resultClass, Type resultType);

  WorkflowExecution start(Object... args);

  <R> WorkflowExecution startWithOperation(
      StartWorkflowAdditionalOperation<R> additionalOperation, Object... args);

  WorkflowExecution signalWithStart(String signalName, Object[] signalArgs, Object[] startArgs);

  Optional<String> getWorkflowType();

  WorkflowExecution getExecution();

  /**
   * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
   * call performs long poll on Temporal service waiting for workflow completion notification.
   *
   * @param resultClass class of the workflow return value
   * @param <R> type of the workflow return value
   * @return workflow return value
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist
   * @throws WorkflowException if workflow failed with an exception
   * @throws WorkflowFailedException if workflow failed. {@link WorkflowFailedException#getCause()}
   *     will be {@link TimeoutFailure}, {@link TerminatedFailure}, {@link CanceledFailure} if the
   *     workflow execution timed out, was cancelled or terminated. Or the original {@link
   *     io.temporal.failure.TemporalFailure} from the workflow that caused the failure otherwise.
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues.
   */
  <R> R getResult(Class<R> resultClass);

  /**
   * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
   * call performs long poll on Temporal service waiting for workflow completion notification.
   *
   * @param resultClass class of the workflow return value
   * @param resultType type of the workflow return value. Differs from resultClass for generic
   *     types.
   * @param <R> type of the workflow return value
   * @return workflow return value
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist
   * @throws WorkflowException if workflow failed with an exception
   * @throws WorkflowFailedException if workflow failed. {@link WorkflowFailedException#getCause()}
   *     will be {@link TimeoutFailure}, {@link TerminatedFailure}, {@link CanceledFailure} if the
   *     workflow execution timed out, was cancelled or terminated. Or the original {@link
   *     io.temporal.failure.TemporalFailure} from the workflow that caused the failure otherwise.
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  <R> R getResult(Class<R> resultClass, Type resultType);

  /**
   * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
   * call performs long poll on Temporal service waiting for workflow completion notification.
   *
   * @param timeout maximum time to wait
   * @param unit unit of timeout
   * @param resultClass class of the workflow return value
   * @param <R> type of the workflow return value
   * @return workflow return value
   * @throws TimeoutException if workflow is not completed after the timeout time
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist
   * @throws WorkflowException if workflow failed with an exception
   * @throws WorkflowFailedException if workflow failed. {@link WorkflowFailedException#getCause()}
   *     will be {@link TimeoutFailure}, {@link TerminatedFailure}, {@link CanceledFailure} if the
   *     workflow execution timed out, was cancelled or terminated. Or the original {@link
   *     io.temporal.failure.TemporalFailure} from the workflow that caused the failure otherwise.
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass) throws TimeoutException;

  /**
   * Returns workflow result potentially waiting for workflow to complete. Behind the scene this
   * call is polling Temporal Server waiting for workflow completion.
   *
   * @param timeout maximum time to wait
   * @param unit unit of timeout
   * @param resultClass class of the workflow return value
   * @param resultType type of the workflow return value. Differs from {@code resultClass} for
   *     generic
   * @param <R> type of the workflow return value
   * @return workflow return value
   * @throws TimeoutException if workflow is not completed after the timeout time
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist
   * @throws WorkflowException if workflow failed with an exception
   * @throws WorkflowFailedException if workflow failed. {@link WorkflowFailedException#getCause()}
   *     will be {@link TimeoutFailure}, {@link TerminatedFailure}, {@link CanceledFailure} if the
   *     workflow execution timed out, was cancelled or terminated. Or the original {@link
   *     io.temporal.failure.TemporalFailure} from the workflow that caused the failure otherwise.
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType)
      throws TimeoutException;

  /**
   * Returns a {@link CompletableFuture} with the workflow result potentially waiting for workflow
   * to complete. Behind the scenes this call performs long polls the Temporal Server waiting for
   * workflow completion.
   *
   * @param resultClass class of the workflow return value
   * @param <R> type of the workflow return value
   * @return future completed with workflow return value or an exception
   * @see #getResult(Class) as a sync version of this method for detailed information about
   *     exceptions that may be thrown from {@link CompletableFuture#get()} wrapped by {@link
   *     ExecutionException}
   */
  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass);

  /**
   * Returns a {@link CompletableFuture} with the workflow result potentially waiting for workflow
   * to complete. Behind the scene this call performs long poll on Temporal service waiting for
   * workflow completion notification.
   *
   * @param resultClass class of the workflow return value
   * @param resultType type of the workflow return value. Differs from {@code resultClass} for
   *     generic types.
   * @param <R> type of the workflow return value
   * @return future completed with workflow return value or an exception
   * @see #getResult(Class, Type) as a sync version of this method for detailed information about
   *     exceptions that may be thrown from {@link CompletableFuture#get()} wrapped by {@link
   *     ExecutionException}
   */
  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, Type resultType);

  /**
   * Returns a {@link CompletableFuture} with the workflow result potentially waiting for workflow
   * to complete. Behind the scene this call performs long poll on Temporal service waiting for
   * workflow completion notification.
   *
   * @param timeout maximum time to wait and perform a background long poll
   * @param unit unit of timeout
   * @param resultClass class of the workflow return value
   * @param <R> type of the workflow return value
   * @return future completed with workflow return value or an exception
   * @see #getResult(long, TimeUnit, Class) as a sync version of this method for detailed
   *     information about exceptions that may be thrown from {@link CompletableFuture#get()}
   *     wrapped by {@link ExecutionException}
   */
  <R> CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit, Class<R> resultClass);

  /**
   * Returns a {@link CompletableFuture} with the workflow result potentially waiting for workflow
   * to complete. Behind the scene this call performs long poll on Temporal service waiting for
   * workflow completion notification.
   *
   * @param timeout maximum time to wait and perform a background long poll
   * @param unit unit of timeout
   * @param resultClass class of the workflow return value
   * @param resultType type of the workflow return value. Differs from {@code resultClass} for
   *     generic types.
   * @param <R> type of the workflow return value
   * @return future completed with workflow return value or an exception
   * @see #getResult(long, TimeUnit, Class, Type) as a sync version of this method for detailed
   *     information about exceptions that may be thrown from {@link CompletableFuture#get()}
   *     wrapped by {@link ExecutionException}
   */
  <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, Type resultType);

  /**
   * Synchronously queries workflow by invoking its query handler. Usually a query handler is a
   * method annotated with {@link io.temporal.workflow.QueryMethod}.
   *
   * @see WorkflowClientOptions.Builder#setQueryRejectCondition(QueryRejectCondition)
   * @param queryType name of the query handler. Usually it is a method name.
   * @param resultClass class of the query result type
   * @param args optional query arguments
   * @param <R> type of the query result
   * @return query result
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist
   * @throws WorkflowQueryException if the query failed during it's execution by the workflow worker
   * @throws WorkflowQueryRejectedException if query is rejected by the server
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  <R> R query(String queryType, Class<R> resultClass, Object... args);

  /**
   * Synchronously queries workflow by invoking its query handler. Usually a query handler is a
   * method annotated with {@link io.temporal.workflow.QueryMethod}.
   *
   * @see WorkflowClientOptions.Builder#setQueryRejectCondition(QueryRejectCondition)
   * @param queryType name of the query handler. Usually it is a method name.
   * @param resultClass class of the query result type
   * @param resultType type of the workflow return value. Differs from {@code resultClass} for
   *     generic types.
   * @param args optional query arguments
   * @param <R> type of the query result
   * @return query result
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist
   * @throws WorkflowQueryException if the query failed during it's execution by the workflow worker
   *     or was rejected on any stage
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args);

  /**
   * Request cancellation of a workflow execution.
   *
   * <p>Cancellation cancels {@link io.temporal.workflow.CancellationScope} that wraps the main
   * workflow method. Note that workflow can take long time to get canceled or even completely
   * ignore the cancellation request.
   *
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist or is already
   *     completed
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  void cancel();

  /**
   * Terminates a workflow execution.
   *
   * <p>Termination is a hard stop of a workflow execution which doesn't give workflow code any
   * chance to perform cleanup.
   *
   * @param reason optional reason for the termination request
   * @param details additional details about the termination reason
   * @throws WorkflowNotFoundException if the workflow execution doesn't exist or is already
   *     completed
   * @throws WorkflowServiceException for all other failures including networking and service
   *     availability issues
   */
  void terminate(@Nullable String reason, Object... details);

  Optional<WorkflowOptions> getOptions();
}
