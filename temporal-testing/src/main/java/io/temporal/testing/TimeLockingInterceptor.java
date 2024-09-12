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

package io.temporal.testing;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.serviceclient.TestServiceStubs;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

class TimeLockingInterceptor extends WorkflowClientInterceptorBase {

  private final IdempotentTimeLocker locker;

  TimeLockingInterceptor(TestServiceStubs testServiceStubs) {
    this.locker = new IdempotentTimeLocker(testServiceStubs);
  }

  @Deprecated
  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next) {
    return new TimeLockingWorkflowStub(locker, next);
  }

  @Deprecated
  @Override
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next) {
    return new TimeLockingWorkflowStub(locker, next);
  }

  static class TimeLockingWorkflowStub implements WorkflowStub {

    private final IdempotentTimeLocker locker;
    private final WorkflowStub next;

    TimeLockingWorkflowStub(IdempotentTimeLocker locker, WorkflowStub next) {
      this.locker = locker;
      this.next = next;
    }

    @Override
    public void signal(String signalName, Object... args) {
      next.signal(signalName, args);
    }

    @Override
    public WorkflowExecution start(Object... args) {
      return next.start(args);
    }

    @Override
    public WorkflowExecution startWithOperation(
        StartWorkflowAdditionalOperation operation, Object... args) {
      return next.startWithOperation(operation, args);
    }

    @Override
    public WorkflowExecution signalWithStart(
        String signalName, Object[] signalArgs, Object[] startArgs) {
      return next.signalWithStart(signalName, signalArgs, startArgs);
    }

    @Override
    public Optional<String> getWorkflowType() {
      return next.getWorkflowType();
    }

    @Override
    public WorkflowExecution getExecution() {
      return next.getExecution();
    }

    @Override
    public <R> R getResult(Class<R> resultClass, Type resultType) {
      locker.unlockTimeSkipping();
      try {
        return next.getResult(resultClass, resultType);
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public <R> R getResult(Class<R> resultClass) {
      locker.unlockTimeSkipping();
      try {
        return next.getResult(resultClass);
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, Type resultType) {
      return new TimeLockingWorkflowStub.TimeLockingFuture<>(
          next.getResultAsync(resultClass, resultType));
    }

    @Override
    public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
      return new TimeLockingWorkflowStub.TimeLockingFuture<>(next.getResultAsync(resultClass));
    }

    @Override
    public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType)
        throws TimeoutException {
      locker.unlockTimeSkipping();
      try {
        return next.getResult(timeout, unit, resultClass, resultType);
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
        throws TimeoutException {
      locker.unlockTimeSkipping();
      try {
        return next.getResult(timeout, unit, resultClass);
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public <R> CompletableFuture<R> getResultAsync(
        long timeout, TimeUnit unit, Class<R> resultClass, Type resultType) {
      return new TimeLockingWorkflowStub.TimeLockingFuture<>(
          next.getResultAsync(timeout, unit, resultClass, resultType));
    }

    @Override
    public <R> CompletableFuture<R> getResultAsync(
        long timeout, TimeUnit unit, Class<R> resultClass) {
      return new TimeLockingWorkflowStub.TimeLockingFuture<>(
          next.getResultAsync(timeout, unit, resultClass));
    }

    @Override
    public <R> R query(String queryType, Class<R> resultClass, Object... args) {
      return next.query(queryType, resultClass, args);
    }

    @Override
    public <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args) {
      return next.query(queryType, resultClass, resultType, args);
    }

    @Override
    public void cancel() {
      next.cancel();
    }

    @Override
    public void terminate(@Nullable String reason, Object... details) {
      next.terminate(reason, details);
    }

    @Override
    public Optional<WorkflowOptions> getOptions() {
      return next.getOptions();
    }

    /** Unlocks time skipping before blocking calls and locks back after completion. */
    private class TimeLockingFuture<R> extends CompletableFuture<R> {

      public TimeLockingFuture(CompletableFuture<R> resultAsync) {
        @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
        CompletableFuture<R> ignored =
            resultAsync.whenComplete(
                (r, e) -> {
                  if (e == null) {
                    this.complete(r);
                  } else {
                    this.completeExceptionally(e);
                  }
                });
      }

      @Override
      public R get() throws InterruptedException, ExecutionException {
        locker.unlockTimeSkipping();
        try {
          return super.get();
        } finally {
          locker.lockTimeSkipping();
        }
      }

      @Override
      public R get(long timeout, TimeUnit unit)
          throws InterruptedException, ExecutionException, TimeoutException {
        locker.unlockTimeSkipping();
        try {
          return super.get(timeout, unit);
        } finally {
          locker.lockTimeSkipping();
        }
      }

      @Override
      public R join() {
        locker.unlockTimeSkipping();
        try {
          return super.join();
        } finally {
          locker.lockTimeSkipping();
        }
      }
    }

    @Override
    public <R> R update(String updateName, Class<R> resultClass, Object... args) {
      return next.update(updateName, resultClass, args);
    }

    @Override
    public <R> WorkflowUpdateHandle<R> startUpdate(
        String updateName, WorkflowUpdateStage waitForStage, Class<R> resultClass, Object... args) {
      return next.startUpdate(updateName, waitForStage, resultClass, args);
    }

    @Override
    public <R> WorkflowUpdateHandle<R> startUpdate(UpdateOptions<R> options, Object... args) {
      return next.startUpdate(options, args);
    }

    @Override
    public <R> WorkflowUpdateHandle<R> getUpdateHandle(String updateId, Class<R> resultClass) {
      return next.getUpdateHandle(updateId, resultClass);
    }

    @Override
    public <R> WorkflowUpdateHandle<R> getUpdateHandle(
        String updateId, Class<R> resultClass, Type resultType) {
      return next.getUpdateHandle(updateId, resultClass, resultType);
    }
  }
}
