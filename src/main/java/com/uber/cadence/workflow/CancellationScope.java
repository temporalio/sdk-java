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

import com.uber.cadence.internal.sync.WorkflowInternal;
import java.util.concurrent.CancellationException;

/**
 * Handle to a cancellation scope created through {@link Workflow#newCancellationScope(Runnable)} or
 * {@link Workflow#newDetachedCancellationScope(Runnable)}. Supports explicit cancelling of the code
 * a cancellation scope wraps. The code in the CancellationScope has to be executed using {@link
 * Runnable#run()} method.
 */
public interface CancellationScope extends Runnable {

  /**
   * When set to false parent thread cancellation causes this one to get cancelled automatically.
   * When set to true only call to {@link #cancel()} leads to this scope cancellation.
   */
  boolean isDetached();

  /** Cancels the scope as well as all its children */
  void cancel();

  /**
   * Cancels the scope as well as all its children.
   *
   * @param reason human readable reason for the cancellation. Becomes message of the
   *     CancellationException thrown.
   */
  void cancel(String reason);

  String getCancellationReason();

  /**
   * Is scope was asked to cancel through {@link #cancel()} or by a parent scope.
   *
   * @return whether request is cancelled or not.
   */
  boolean isCancelRequested();

  /**
   * Use this promise to perform cancellation of async operations.
   *
   * @return promise that becomes ready when scope is cancelled. It contains reason value or null if
   *     none was provided.
   */
  Promise<String> getCancellationRequest();

  static CancellationScope current() {
    return WorkflowInternal.currentCancellationScope();
  }

  /**
   * Throws {@link java.util.concurrent.CancellationException} if scope is cancelled. Noop if not
   * cancelled.
   */
  static void throwCancelled() throws CancellationException {
    if (current().isCancelRequested()) {
      throw new CancellationException();
    }
  }
}
