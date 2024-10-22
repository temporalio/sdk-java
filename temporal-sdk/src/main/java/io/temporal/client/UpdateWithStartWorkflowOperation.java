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

import io.temporal.common.Experimental;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * UpdateWithStartWorkflowOperation is an update workflow request that can be executed together with
 * a start workflow request.
 */
@Experimental
public final class UpdateWithStartWorkflowOperation<R> {

  private final AtomicBoolean invoked = new AtomicBoolean(false);

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a zero argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   */
  public static <R> Builder<R> newBuilder(Functions.Func<R> request) {
    return new Builder<>(
        () -> {
          request.apply();
        });
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a one argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   */
  public static <A1, R> Builder<R> newBuilder(Functions.Func1<A1, R> request, A1 arg1) {
    return new Builder<>(() -> request.apply(arg1));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a two argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   */
  public static <A1, A2, R> Builder<R> newBuilder(
      Functions.Func2<A1, A2, R> request, A1 arg1, A2 arg2) {
    return new Builder<>(() -> request.apply(arg1, arg2));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a three argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   */
  public static <A1, A2, A3, R> Builder<R> newBuilder(
      Functions.Func3<A1, A2, A3, R> request, A1 arg1, A2 arg2, A3 arg3) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a four argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   */
  public static <A1, A2, A3, A4, R> Builder<R> newBuilder(
      Functions.Func4<A1, A2, A3, A4, R> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a five argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 fifth request function parameter
   */
  public static <A1, A2, A3, A4, A5, R> Builder<R> newBuilder(
      Functions.Func5<A1, A2, A3, A4, A5, R> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4, arg5));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a six argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 fifth request function parameter
   * @param arg6 sixth request function parameter
   */
  public static <A1, A2, A3, A4, A5, A6, R> Builder<R> newBuilder(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> request,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a zero argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   */
  public static Builder<Void> newBuilder(Functions.Proc request) {
    return new Builder<>(
        () -> {
          request.apply();
        });
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a one argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   */
  public static <A1> Builder<Void> newBuilder(Functions.Proc1<A1> request, A1 arg1) {
    return new Builder<>(() -> request.apply(arg1));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a two argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   */
  public static <A1, A2> Builder<Void> newBuilder(
      Functions.Proc2<A1, A2> request, A1 arg1, A2 arg2) {
    return new Builder<>(() -> request.apply(arg1, arg2));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a three argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   */
  public static <A1, A2, A3> Builder<Void> newBuilder(
      Functions.Proc3<A1, A2, A3> request, A1 arg1, A2 arg2, A3 arg3) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a four argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   */
  public static <A1, A2, A3, A4> Builder<Void> newBuilder(
      Functions.Proc4<A1, A2, A3, A4> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a five argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 fifth request function parameter
   */
  public static <A1, A2, A3, A4, A5> Builder<Void> newBuilder(
      Functions.Proc5<A1, A2, A3, A4, A5> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4, arg5));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation} for a six argument
   * request.
   *
   * @param request method reference annotated with @UpdateMethod of a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 fifth request function parameter
   * @param arg6 sixth request function parameter
   */
  public static <A1, A2, A3, A4, A5, A6> Builder<Void> newBuilder(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> request,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  /**
   * Returns a new builder for an {@link UpdateWithStartWorkflowOperation}.
   *
   * @param updateName name of the update handler
   * @param resultClass class of the update return value
   * @param args update request arguments
   */
  public static <R> Builder<R> newBuilder(String updateName, Class<R> resultClass, Object[] args) {
    return new Builder<>(
        UpdateOptions.<R>newBuilder().setResultClass(resultClass).setUpdateName(updateName), args);
  }

  private WorkflowStub stub;

  private UpdateOptions<R> options;

  // set by constructor (untyped) or `prepareUpdate` (typed)
  private Object[] updateArgs;

  // set by `prepareStart`
  private Object[] workflowArgs;

  private final CompletableFuture<WorkflowUpdateHandle<R>> handle;

  @Nullable private final Functions.Proc updateRequest;

  private UpdateWithStartWorkflowOperation(
      UpdateOptions<R> options, Functions.Proc updateRequest, Object[] updateArgs) {
    this.options = options;
    this.updateArgs = updateArgs;
    this.handle = new CompletableFuture<>();
    this.updateRequest = updateRequest;
  }

  WorkflowUpdateHandle<R> invoke(Functions.Proc workflowRequest) {
    WorkflowInvocationHandler.initAsyncInvocation(
        WorkflowInvocationHandler.InvocationType.UPDATE_WITH_START, this);
    try {
      // invokes `prepareStart` via WorkflowInvocationHandler.UpdateWithStartInvocationHandler
      workflowRequest.apply();

      if (updateRequest != null) { // only present when using typed API
        // invokes `prepareUpdate` via WorkflowInvocationHandler.UpdateWithStartInvocationHandler
        updateRequest.apply();
      }
      stub.updateWithStart(this, this.workflowArgs);
      return this.handle.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw (cause instanceof RuntimeException
          ? (RuntimeException) cause
          : new RuntimeException(cause));
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  /** Invoked by {@link WorkflowInvocationHandler.UpdateWithStartInvocationHandler}. */
  void prepareUpdate(
      WorkflowStub stub, String updateName, Class resultClass, Type resultType, Object[] args) {
    setStub(stub);
    this.updateArgs = args;
    this.options =
        this.options.toBuilder()
            .setUpdateName(updateName)
            .setResultClass(resultClass)
            .setResultType(resultType)
            .build();
  }

  /** Invoked by {@link WorkflowInvocationHandler.UpdateWithStartInvocationHandler}. */
  void prepareStart(WorkflowStub stub, Object[] args) {
    setStub(stub);
    this.workflowArgs = args;
  }

  /** Returns the result of the update request. */
  public R getResult() throws ExecutionException, InterruptedException {
    return this.getUpdateHandle().get().getResultAsync().get();
  }

  public Future<WorkflowUpdateHandle<R>> getUpdateHandle() {
    return this.handle;
  }

  void setUpdateHandle(WorkflowUpdateHandle<R> updateHandle) {
    this.handle.complete(updateHandle);
  }

  public UpdateOptions<R> getOptions() {
    return this.options;
  }

  public Object[] getUpdateArgs() {
    return this.updateArgs;
  }

  // equals/hashCode intentionally left as default

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("UpdateWithStartWorkflowOperation{options=").append(options);
    if (updateRequest != null) {
      sb.append(", updateRequest=").append(updateRequest);
    }
    if (updateArgs != null) {
      sb.append(", updateArgs=").append(Arrays.toString(updateArgs));
    }
    return sb.toString();
  }

  private void setStub(WorkflowStub stub) {
    if (this.stub != null && stub != this.stub) {
      throw new IllegalArgumentException(
          "UpdateWithStartWorkflowOperation invoked on different workflow stubs");
    }
    this.stub = stub;
  }

  /**
   * Mark the operation as having been invoked.
   *
   * @return false if the operation was already invoked
   */
  boolean markInvoked() {
    return invoked.compareAndSet(false, true);
  }

  public static final class Builder<R> {
    private UpdateOptions.Builder<R> options;
    private Functions.Proc request;
    private Object[] args;

    private Builder() {}

    private Builder(UpdateOptions.Builder<R> options, Object[] args) {
      this.options = options;
      this.request = null;
      this.args = args;
    }

    private Builder(Functions.Proc request) {
      this.options = UpdateOptions.newBuilder();
      this.request = request;
    }

    public Builder<R> setWaitForStage(WorkflowUpdateStage waitForStage) {
      this.options.setWaitForStage(waitForStage);
      return this;
    }

    public Builder<R> setUpdateId(String updateId) {
      this.options.setUpdateId(updateId);
      return this;
    }

    public UpdateWithStartWorkflowOperation<R> build() {
      return new UpdateWithStartWorkflowOperation<>(this.options.build(), this.request, this.args);
    }
  }
}
