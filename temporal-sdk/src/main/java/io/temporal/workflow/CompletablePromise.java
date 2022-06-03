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
   * source.handle((value, failure) -&gt; {
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
