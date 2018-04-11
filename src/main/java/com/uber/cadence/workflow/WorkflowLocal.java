/*
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

package com.uber.cadence.workflow;

import com.uber.cadence.internal.sync.RunnerLocalInternal;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A value that is local to a single workflow execution. So it can act as a <i>global</i> variable
 * for the workflow code. For example the {@code Workflow.isSignaled()} static method returns the
 * correct value even if there are multiple workflows executing on the same machine simultaneously.
 * It would be invalid if the {@code signaled} was a {@code static boolean} variable.
 *
 * <pre>{@code
 * public class Workflow {
 *
 *   private static final WorkflowLocal<Boolean> signaled = WorkflowLocal.withInitial(() -> false);
 *
 *   public static boolean isSignaled() {
 *     return signaled.get();
 *   }
 *
 *   public void signal() {
 *     signaled.set(true);
 *   }
 * }
 * }</pre>
 *
 * @see WorkflowThreadLocal for thread local that can be used inside workflow code.
 */
public final class WorkflowLocal<T> {

  private final RunnerLocalInternal<T> impl = new RunnerLocalInternal<>();
  private Supplier<? extends T> supplier;

  private WorkflowLocal(Supplier<? extends T> supplier) {
    this.supplier = Objects.requireNonNull(supplier);
  }

  public WorkflowLocal() {
    this.supplier = () -> null;
  }

  public static <S> WorkflowLocal<S> withInitial(Supplier<? extends S> supplier) {
    return new WorkflowLocal<>(supplier);
  }

  public T get() {
    return impl.get(supplier);
  }

  public void set(T value) {
    impl.set(value);
  }
}
