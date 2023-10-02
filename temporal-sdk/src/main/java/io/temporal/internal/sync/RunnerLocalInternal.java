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

public final class RunnerLocalInternal<T> {

  private final boolean useCaching;

  public RunnerLocalInternal() {
    this.useCaching = false;
  }

  public RunnerLocalInternal(boolean useCaching) {
    this.useCaching = useCaching;
  }

  public T get(Supplier<? extends T> supplier) {
    Optional<Optional<T>> result =
        DeterministicRunnerImpl.currentThreadInternal().getRunner().getRunnerLocal(this);
    T out = result.orElseGet(() -> Optional.ofNullable(supplier.get())).orElse(null);
    if (!result.isPresent() && useCaching) {
      // This is the first time we've tried fetching this, and caching is enabled. Store it.
      set(out);
    }
    return out;
  }

  public void set(T value) {
    DeterministicRunnerImpl.currentThreadInternal().getRunner().setRunnerLocal(this, value);
  }
}
