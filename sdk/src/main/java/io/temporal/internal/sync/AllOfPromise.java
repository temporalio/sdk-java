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

import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AllOfPromise implements Promise<Void> {

  private final CompletablePromise<Void> impl = Workflow.newPromise();
  private int notReadyCount;

  AllOfPromise(Promise<?>[] promises) {
    for (Promise<?> f : promises) {
      addPromise(f);
    }
    if (notReadyCount == 0) {
      impl.complete(null);
    }
  }

  public <V> AllOfPromise(Iterable<Promise<V>> promises) {
    for (Promise<?> f : promises) {
      addPromise(f);
    }
    if (notReadyCount == 0) {
      impl.complete(null);
    }
  }

  private void addPromise(Promise<?> f) {
    if (!f.isCompleted()) {
      notReadyCount++;
      f.handle(
          (r, e) -> {
            if (notReadyCount == 0) {
              throw new Error("Unexpected 0 count");
            }
            if (impl.isCompleted()) {
              return null;
            }
            if (e != null) {
              impl.completeExceptionally(e);
            }
            if (--notReadyCount == 0) {
              impl.complete(null);
            }
            return null;
          });
    }
  }

  @Override
  public boolean isCompleted() {
    return impl.isCompleted();
  }

  @Override
  public Void get() {
    return impl.get();
  }

  @Override
  public Void cancellableGet() {
    return impl.cancellableGet();
  }

  @Override
  public Void cancellableGet(long timeout, TimeUnit unit) throws TimeoutException {
    return impl.cancellableGet(timeout, unit);
  }

  @Override
  public Void get(long timeout, TimeUnit unit) throws TimeoutException {
    return impl.get(timeout, unit);
  }

  @Override
  public RuntimeException getFailure() {
    return impl.getFailure();
  }

  @Override
  public <U> Promise<U> thenApply(Functions.Func1<? super Void, ? extends U> fn) {
    return impl.thenApply(fn);
  }

  @Override
  public <U> Promise<U> handle(Functions.Func2<? super Void, RuntimeException, ? extends U> fn) {
    return impl.handle(fn);
  }

  @Override
  public <U> Promise<U> thenCompose(Functions.Func1<? super Void, ? extends Promise<U>> fn) {
    return impl.thenCompose(fn);
  }

  @Override
  public Promise<Void> exceptionally(Functions.Func1<Throwable, ? extends Void> fn) {
    return impl.exceptionally(fn);
  }
}
