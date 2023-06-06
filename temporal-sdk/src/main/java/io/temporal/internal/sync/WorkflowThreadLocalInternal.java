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

package io.temporal.internal.sync;

import java.util.Optional;
import java.util.function.Supplier;

public final class WorkflowThreadLocalInternal<T> {

  private T supplierResult = null;
  private boolean supplierCalled = false;

  Optional<T> invokeSupplier(Supplier<? extends T> supplier) {
    if (!supplierCalled) {
      T result = supplier.get();
      supplierCalled = true;
      supplierResult = result;
      return Optional.ofNullable(result);
    } else {
      return Optional.ofNullable(supplierResult);
    }
  }

  public T get(Supplier<? extends T> supplier) {
    Optional<Optional<T>> result =
        DeterministicRunnerImpl.currentThreadInternal().getThreadLocal(this);
    return result.orElseGet(() -> invokeSupplier(supplier)).orElse(null);
  }

  public void set(T value) {
    DeterministicRunnerImpl.currentThreadInternal().setThreadLocal(this, value);
  }
}
