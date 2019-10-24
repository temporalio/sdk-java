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

/** {@link Promise} that exposes completion methods. */
public interface CompletablePromise<V> extends Promise<V> {

  /**
   * Completes this Promise with a value if not yet done.
   *
   * @return true if wasn't already completed.
   */
  boolean complete(V value);

  /**
   * Completes this Promise with a an exception if not yet done.
   *
   * @return true if wasn't already completed.
   */
  boolean completeExceptionally(RuntimeException value);

  /**
   * Completes or completes exceptionally this promise from the source promise when it becomes
   * completed.
   *
   * <pre><code>
   * destination.completeFrom(source);
   * </code></pre>
   *
   * Is shortcut to:
   *
   * <pre><code>
   * source.handle((value, failure) -> {
   *    if (failure != null) {
   *       destination.completeExceptionally(failure);
   *    } else {
   *       destination.complete(value);
   *    }
   *    return null;
   * }
   * </code></pre>
   *
   * @param source promise that is being watched.
   * @return false if source already completed, otherwise return true or null
   */
  boolean completeFrom(Promise<V> source);
}
