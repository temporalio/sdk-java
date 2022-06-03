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

package io.temporal.workflow;

import io.temporal.internal.sync.WorkflowThreadLocalInternal;
import java.util.Objects;
import java.util.function.Supplier;

/** {@link ThreadLocal} analog for workflow code. */
public final class WorkflowThreadLocal<T> {

  private final WorkflowThreadLocalInternal<T> impl = new WorkflowThreadLocalInternal<>();
  private final Supplier<? extends T> supplier;

  private WorkflowThreadLocal(Supplier<? extends T> supplier) {
    this.supplier = Objects.requireNonNull(supplier);
  }

  public WorkflowThreadLocal() {
    this.supplier = () -> null;
  }

  public static <S> WorkflowThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
    return new WorkflowThreadLocal<>(supplier);
  }

  public T get() {
    return impl.get(supplier);
  }

  public void set(T value) {
    impl.set(value);
  }
}
