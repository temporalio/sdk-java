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

import io.temporal.failure.CanceledFailure;
import io.temporal.internal.sync.WorkflowInternal;

/**
 * Handle to a cancellation scope created through {@link Workflow#newCancellationScope(Runnable)} or
 * {@link Workflow#newDetachedCancellationScope(Runnable)}. Supports explicit cancelling of the code
 * a cancellation scope wraps. The code in the CancellationScope has to be executed using {@link
 * Runnable#run()} method.
 */
public interface CancellationScope extends Runnable {

  /**
   * When set to false parent thread cancellation causes this one to get canceled automatically.
   * When set to true only call to {@link #cancel()} leads to this scope cancellation.
   */
  boolean isDetached();

  /** Cancels the scope as well as all its children */
  void cancel();

  /**
   * Cancels the scope as well as all its children.
   *
   * @param reason human readable reason for the cancellation. Becomes message of the
   *     CanceledException thrown.
   */
  void cancel(String reason);

  String getCancellationReason();

  /**
   * Is scope was asked to cancel through {@link #cancel()} or by a parent scope.
   *
   * @return whether request is canceled or not.
   */
  boolean isCancelRequested();

  /**
   * Use this promise to perform cancellation of async operations.
   *
   * @return promise that becomes ready when scope is canceled. It contains reason value or null if
   *     none was provided.
   */
  Promise<String> getCancellationRequest();

  static CancellationScope current() {
    return WorkflowInternal.currentCancellationScope();
  }

  /** Throws {@link CanceledFailure} if scope is canceled. Noop if not canceled. */
  static void throwCanceled() throws CanceledFailure {
    if (current().isCancelRequested()) {
      throw new CanceledFailure(current().getCancellationReason());
    }
  }
}
