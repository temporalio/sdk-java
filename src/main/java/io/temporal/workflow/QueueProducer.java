/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.workflow;

import java.time.Duration;

public interface QueueProducer<E> {

  /**
   * Inserts the specified element into this queue if it is possible to do so immediately without
   * violating capacity restrictions, returning {@code true} upon success and {@code false} if no
   * space is currently available.
   *
   * @param e the element to add
   * @return {@code true} if the element was added to this queue, else {@code false}
   * @throws ClassCastException if the class of the specified element prevents it from being added
   *     to this queue
   * @throws NullPointerException if the specified element is null
   * @throws IllegalArgumentException if some property of the specified element prevents it from
   *     being added to this queue
   */
  boolean offer(E e);

  /**
   * Inserts the specified element into this queue, waiting if necessary for space to become
   * available. It is not unblocked in case of the enclosing CancellationScope cancellation. Use
   * {@link #cancellablePut(Object)} instead.
   *
   * @param e the element to add
   * @throws ClassCastException if the class of the specified element prevents it from being added
   *     to this queue
   * @throws NullPointerException if the specified element is null
   * @throws IllegalArgumentException if some property of the specified element prevents it from
   *     being added to this queue
   */
  void put(E e);

  /**
   * Inserts the specified element into this queue, waiting if necessary for space to become
   * available.
   *
   * @param e the element to add
   * @throws io.temporal.failure.CanceledFailure if surrounding @{@link CancellationScope} is
   *     cancelled while waiting
   * @throws ClassCastException if the class of the specified element prevents it from being added
   *     to this queue
   * @throws NullPointerException if the specified element is null
   * @throws IllegalArgumentException if some property of the specified element prevents it from
   *     being added to this queue
   */
  void cancellablePut(E e);

  /**
   * Inserts the specified element into this queue, waiting up to the specified wait time if
   * necessary for space to become available. It is not unblocked in case of the enclosing
   * CancellationScope cancellation. Use {@link #cancellableOffer(Object, Duration)} instead.
   *
   * @param e the element to add
   * @param timeout how long to wait before giving up
   * @return {@code true} if successful, or {@code false} if the specified waiting time elapses
   *     before space is available
   * @throws ClassCastException if the class of the specified element prevents it from being added
   *     to this queue
   * @throws NullPointerException if the specified element is null
   * @throws IllegalArgumentException if some property of the specified element prevents it from
   *     being added to this queue
   */
  boolean offer(E e, Duration timeout);

  /**
   * Inserts the specified element into this queue, waiting up to the specified wait time if
   * necessary for space to become available.
   *
   * @param e the element to add
   * @param timeout how long to wait before giving up
   * @return {@code true} if successful, or {@code false} if the specified waiting time elapses
   *     before space is available
   * @throws io.temporal.failure.CanceledFailure if surrounding @{@link CancellationScope} is
   *     cancelled while waiting
   * @throws ClassCastException if the class of the specified element prevents it from being added
   *     to this queue
   * @throws NullPointerException if the specified element is null
   * @throws IllegalArgumentException if some property of the specified element prevents it from
   *     being added to this queue
   */
  boolean cancellableOffer(E e, Duration timeout);
}
