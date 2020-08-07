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

import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class CompletablePromiseImpl<V> implements CompletablePromise<V> {

  private V value;
  private RuntimeException failure;
  private boolean completed;
  private final List<Functions.Proc> handlers = new ArrayList<>();
  private final DeterministicRunnerImpl runner;
  private boolean registeredWithRunner;

  @SuppressWarnings("unchecked")
  static Promise<Object> promiseAnyOf(Promise<?>[] promises) {
    CompletablePromise<Object> result = Workflow.newPromise();
    for (Promise<?> p : promises) {
      // Rely on the fact that promise ignores all duplicated completions.
      result.completeFrom((Promise<Object>) p);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  static Promise<Object> promiseAnyOf(Iterable<Promise<?>> promises) {
    CompletablePromise<Object> result = Workflow.newPromise();
    for (Promise<?> p : promises) {
      // Rely on the fact that promise ignores all duplicated completions.
      result.completeFrom((Promise<Object>) p);
    }
    return result;
  }

  CompletablePromiseImpl() {
    runner = DeterministicRunnerImpl.currentThreadInternal().getRunner();
  }

  @Override
  public boolean isCompleted() {
    return completed;
  }

  @Override
  public V get() {
    return getImpl(false);
  }

  @Override
  public V cancellableGet() {
    return getImpl(true);
  }

  private V getImpl(boolean cancellable) {
    if (!completed) {
      WorkflowThread.await(
          "Feature.get",
          () -> {
            if (cancellable) {
              CancellationScope.throwCancelled();
            }
            return completed;
          });
    }
    if (failure != null) {
      unregisterWithRunner();
      throwFailure();
    }
    return value;
  }

  @Override
  public V get(long timeout, TimeUnit unit) throws TimeoutException {
    return cancellableGetImpl(false, timeout, unit);
  }

  @Override
  public V cancellableGet(long timeout, TimeUnit unit) throws TimeoutException {
    return cancellableGetImpl(true, timeout, unit);
  }

  public V cancellableGetImpl(boolean cancellable, long timeout, TimeUnit unit)
      throws TimeoutException {
    if (!completed) {
      WorkflowInternal.await(
          Duration.ofMillis(timeout),
          "Feature.get",
          () -> {
            if (cancellable) {
              CancellationScope.throwCancelled();
            }
            return completed;
          });
    }
    if (!completed) {
      throw new TimeoutException();
    }
    if (failure != null) {
      unregisterWithRunner();
      return throwFailure();
    }
    return value;
  }

  private V throwFailure() {
    // Replace confusing async stack with the current one.
    if (failure instanceof TemporalFailure) {
      failure.setStackTrace(Thread.currentThread().getStackTrace());
    }
    throw failure;
  }

  @Override
  public RuntimeException getFailure() {
    if (!completed) {
      WorkflowThread.await("Feature.get", () -> completed);
    }
    if (failure != null) {
      unregisterWithRunner();
      return failure;
    }
    return null;
  }

  private void unregisterWithRunner() {
    if (registeredWithRunner) {
      runner.forgetFailedPromise(this);
      registeredWithRunner = false;
    }
  }

  @Override
  public boolean complete(V value) {
    if (completed) {
      return false;
    }
    this.completed = true;
    this.value = value;
    invokeHandlers();
    return true;
  }

  @Override
  public boolean completeExceptionally(RuntimeException value) {
    if (completed) {
      return false;
    }
    this.completed = true;
    this.failure = value;
    boolean invoked = invokeHandlers();
    if (!invoked) {
      runner.registerFailedPromise(this); // To ensure that failure is not ignored
      registeredWithRunner = true;
    }
    return true;
  }

  @Override
  public boolean completeFrom(Promise<V> source) {
    if (completed) {
      return false;
    }
    source.handle(
        (value, failure) -> {
          if (failure != null) {
            this.completeExceptionally(failure);
          } else {
            this.complete(value);
          }
          return null;
        });
    return true;
  }

  @Override
  public <U> Promise<U> thenApply(Functions.Func1<? super V, ? extends U> fn) {
    return handle(
        (r, e) -> {
          if (e != null) {
            throw e;
          }
          return fn.apply(r);
        });
  }

  @Override
  public <U> Promise<U> handle(Functions.Func2<? super V, RuntimeException, ? extends U> fn) {
    return then(
        (result) -> {
          try {
            U r = fn.apply(value, failure);
            result.complete(r);
          } catch (RuntimeException e) {
            result.completeExceptionally(e);
          }
        });
  }

  @Override
  public <U> Promise<U> thenCompose(Functions.Func1<? super V, ? extends Promise<U>> fn) {
    return then(
        (result) -> {
          if (failure != null) {
            result.completeExceptionally(failure);
            return;
          }
          try {
            Promise<U> r = fn.apply(value);
            result.completeFrom(r);
          } catch (RuntimeException e) {
            result.completeExceptionally(e);
          }
        });
  }

  @Override
  public Promise<V> exceptionally(Functions.Func1<Throwable, ? extends V> fn) {
    return then(
        (result) -> {
          if (failure == null) {
            result.complete(value);
            return;
          }
          try {
            V r = fn.apply(failure);
            result.complete(r);
          } catch (RuntimeException e) {
            result.completeExceptionally(e);
          }
        });
  }

  /** Call proc immediately if ready or register with handlers. */
  private <U> Promise<U> then(Functions.Proc1<CompletablePromise<U>> proc) {
    CompletablePromise<U> resultPromise = new CompletablePromiseImpl<>();
    if (completed) {
      proc.apply(resultPromise);
      unregisterWithRunner();
    } else {
      handlers.add(() -> proc.apply(resultPromise));
    }
    return resultPromise;
  }

  /** @return true if there were any handlers invoked */
  private boolean invokeHandlers() {
    for (Functions.Proc handler : handlers) {
      handler.apply();
    }
    return !handlers.isEmpty();
  }
}
