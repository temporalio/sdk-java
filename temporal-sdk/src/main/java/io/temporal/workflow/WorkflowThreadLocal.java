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

  private final WorkflowThreadLocalInternal<T> impl;
  private final Supplier<? extends T> supplier;

  private WorkflowThreadLocal(Supplier<? extends T> supplier, boolean useCaching) {
    this.supplier = Objects.requireNonNull(supplier);
    this.impl = new WorkflowThreadLocalInternal<>(useCaching);
  }

  public WorkflowThreadLocal() {
    this.supplier = () -> null;
    this.impl = new WorkflowThreadLocalInternal<>(false);
  }

  /**
   * Create an instance that returns the value returned by the given {@code Supplier} when {@link
   * #set(S)} has not yet been called in the thread. Note that the value returned by the {@code
   * Supplier} is not stored in the {@code WorkflowThreadLocal} implicitly; repeatedly calling
   * {@link #get()} will always re-execute the {@code Supplier} until you call {@link #set(S)} for
   * the first time. This differs from the behavior of {@code ThreadLocal}. If you want the value
   * returned by the {@code Supplier} to be stored in the {@code WorkflowThreadLocal}, which matches
   * the behavior of {@code ThreadLocal}, use {@link #withCachedInitial(Supplier)} instead.
   *
   * @param supplier Callback that will be executed whenever {@link #get()} is called, until {@link
   *     #set(S)} is called for the first time.
   * @return A {@code WorkflowThreadLocal} instance.
   * @param <S> The type stored in the {@code WorkflowThreadLocal}.
   * @deprecated Because the non-caching behavior of this API is typically not desirable, it's
   *     recommend to use {@link #withCachedInitial(Supplier)} instead.
   */
  @Deprecated
  public static <S> WorkflowThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
    return new WorkflowThreadLocal<>(supplier, false);
  }

  /**
   * Create an instance that returns the value returned by the given {@code Supplier} when {@link
   * #set(S)} has not yet been called in the Workflow, and then stores the returned value inside the
   * {@code WorkflowThreadLocal}.
   *
   * @param supplier Callback that will be executed when {@link #get()} is called for the first
   *     time, if {@link #set(S)} has not already been called.
   * @return A {@code WorkflowThreadLocal} instance.
   * @param <S> The type stored in the {@code WorkflowThreadLocal}.
   */
  public static <S> WorkflowThreadLocal<S> withCachedInitial(Supplier<? extends S> supplier) {
    return new WorkflowThreadLocal<>(supplier, true);
  }

  public T get() {
    return impl.get(supplier);
  }

  public void set(T value) {
    impl.set(value);
  }
}
