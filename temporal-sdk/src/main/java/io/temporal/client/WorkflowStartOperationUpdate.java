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
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.*;

/** TBD */
public class WorkflowStartOperationUpdate<R> extends StartWorkflowAdditionalOperation {

  public static <T> WorkflowStartOperationUpdate.Builder<T> newBuilder() {
    return new WorkflowStartOperationUpdate.Builder<T>();
  }

  public static <R> Builder<R> newBuilder(Functions.Func<R> request) {
    return new Builder<>(
        () -> {
          request.apply();
        });
  }

  public static <A1, R> Builder<R> newBuilder(Functions.Func1<A1, R> request, A1 arg1) {
    return new Builder<>(() -> request.apply(arg1));
  }

  public static <A1, A2, R> Builder<R> newBuilder(
      Functions.Func2<A1, A2, R> request, A1 arg1, A2 arg2) {
    return new Builder<>(() -> request.apply(arg1, arg2));
  }

  public static <A1, A2, A3, R> Builder<R> newBuilder(
      Functions.Func3<A1, A2, A3, R> request, A1 arg1, A2 arg2, A3 arg3) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4, R> Builder<R> newBuilder(
      Functions.Func4<A1, A2, A3, A4, R> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5, R> Builder<R> newBuilder(
      Functions.Func5<A1, A2, A3, A4, A5, R> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4, arg5));
  }

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

  public static <A1> Builder<Void> newBuilder(Functions.Proc1<A1> request, A1 arg1) {
    return new Builder<>(() -> request.apply(arg1));
  }

  public static <A1, A2> Builder<Void> newBuilder(
      Functions.Proc2<A1, A2> request, A1 arg1, A2 arg2) {
    return new Builder<>(() -> request.apply(arg1, arg2));
  }

  public static <A1, A2, A3> Builder<Void> newBuilder(
      Functions.Proc3<A1, A2, A3> request, A1 arg1, A2 arg2, A3 arg3) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4> Builder<Void> newBuilder(
      Functions.Proc4<A1, A2, A3, A4> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5> Builder<Void> newBuilder(
      Functions.Proc5<A1, A2, A3, A4, A5> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return new Builder<>(() -> request.apply(arg1, arg2, arg3, arg4, arg5));
  }

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

  public static <R> Builder<R> newBuilder(String updateName, Class<R> resultType, Object[] args) {
    return new Builder<>(
        UpdateOptions.<R>newBuilder().setResultClass(resultType).setUpdateName(updateName), args);
  }

  private WorkflowStub stub;

  private UpdateOptions<R> options;

  private Object[] updateArgs;

  private final CompletableFuture<WorkflowUpdateHandle<R>> handle;

  private final Functions.Proc request;

  public WorkflowStartOperationUpdate(
      UpdateOptions<R> options, Functions.Proc request, Object[] updateArgs) {
    this.options = options;
    this.updateArgs = updateArgs;
    this.handle = new CompletableFuture<>();
    this.request = request;
  }

  @Override
  WorkflowExecution invoke(Functions.Proc workflow) {
    WorkflowInvocationHandler.initAsyncInvocation(
        WorkflowInvocationHandler.InvocationType.UPDATE_WITH_START, this);
    try {
      request.apply();
      workflow.apply();
      return stub.startWithOperation(this, this.updateArgs);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

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

  void prepareStart(WorkflowStub stub) {
    setStub(stub);
  }

  public R getResult() throws ExecutionException, InterruptedException {
    return this.getUpdateHandle().get().getResultAsync().get();
  }

  public Future<WorkflowUpdateHandle<R>> getUpdateHandle() {
    return this.handle;
  }

  public void setUpdateHandle(WorkflowUpdateHandle<R> updateHandle) {
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
    sb.append("WorkflowStartOperationUpdate{options=").append(options);
    if (request != null) {
      sb.append(", request=").append(request);
    }
    if (updateArgs != null) {
      sb.append(", updateArgs=").append(Arrays.toString(updateArgs));
    }
    return sb.toString();
  }

  private void setStub(WorkflowStub stub) {
    if (this.stub != null && stub != this.stub) {
      throw new IllegalArgumentException(
          "WorkflowStartOperationUpdate invoked on different workflow stubs");
    }
    this.stub = stub;
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

    public WorkflowStartOperationUpdate<R> build() {
      return new WorkflowStartOperationUpdate<>(this.options.build(), this.request, this.args);
    }
  }
}
