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

import io.temporal.workflow.Functions;
import io.temporal.workflow.NexusOperationHandle;
import java.util.concurrent.atomic.AtomicReference;

public class StartNexusCallInternal {

  private static final ThreadLocal<AtomicReference<NexusOperationHandle<?>>> asyncResult =
      new ThreadLocal<>();

  public static boolean isAsync() {
    return asyncResult.get() != null;
  }

  public static <R> void setAsyncResult(NexusOperationHandle<R> handle) {
    AtomicReference<NexusOperationHandle<?>> placeholder = asyncResult.get();
    if (placeholder == null) {
      throw new IllegalStateException("not in invoke invocation");
    }
    placeholder.set(handle);
  }

  /**
   * Indicate to the dynamic interface implementation that call was done through
   *
   * @link Async#invoke}. Must be closed through {@link #closeAsyncInvocation()}
   */
  public static void initAsyncInvocation() {
    if (asyncResult.get() != null) {
      throw new IllegalStateException("already in start invocation");
    }
    asyncResult.set(new AtomicReference<>());
  }

  /**
   * @return asynchronous result of an invocation.
   */
  private static <T> NexusOperationHandle<T> getAsyncInvocationResult() {
    AtomicReference<NexusOperationHandle<?>> reference = asyncResult.get();
    if (reference == null) {
      throw new IllegalStateException("initAsyncInvocation wasn't called");
    }
    @SuppressWarnings("unchecked")
    NexusOperationHandle<T> result = (NexusOperationHandle<T>) reference.get();
    if (result == null) {
      throw new IllegalStateException("start result wasn't set");
    }
    return result;
  }

  /** Closes async invocation created through {@link #initAsyncInvocation()} */
  public static void closeAsyncInvocation() {
    asyncResult.remove();
  }

  public static <T, R> NexusOperationHandle<R> startNexusOperation(Functions.Proc operation) {
    initAsyncInvocation();
    try {
      operation.apply();
      return getAsyncInvocationResult();
    } finally {
      closeAsyncInvocation();
    }
  }
}
