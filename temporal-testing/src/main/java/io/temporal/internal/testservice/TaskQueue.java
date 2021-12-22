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

package io.temporal.internal.testservice;

import java.util.LinkedList;

/**
 * An unbounded blocking queue supporting two states: open and closed. When the queue is open it
 * operates as a normal blocking queue. When the queue has been closed, calls to push have no effect
 * and all calls to pop return immediately with a null value. Calls to pop that were blocked at the
 * time that the queue is closed are woken up and also return null. Once closed, a queue cannot
 * transition back into the open state.
 *
 * @param <E> the element type of this queue
 */
class TaskQueue<E> {
  private final LinkedList<E> queue = new LinkedList<>();
  private boolean closed = false;

  /**
   * Creates a new TaskQueue instance that is already in the closed state.
   *
   * @param <E> the element type of the instantiated queue
   * @return A closed TaskQueue
   */
  static <E> TaskQueue<E> closed() {
    final TaskQueue<E> queue = new TaskQueue<>();
    queue.close();
    return queue;
  }

  /**
   * Pushes element onto the tail of the queue unless the queue has been closed in which case there
   * is no effect.
   *
   * @param element the value to add
   */
  synchronized void push(E element) {
    if (this.closed) {
      return;
    }
    this.queue.push(element);
    this.notifyAll();
  }

  /**
   * Removes an element from the head of the queue and returns it. This call blocks while the queue
   * is empty and returns null only in the case that the queue is closed.
   *
   * @return null if the queue has been closed, otherwise the head element of the queue
   * @throws InterruptedException if the calling thread is interrupted while the pop is blocked
   */
  synchronized E pop() throws InterruptedException {
    while (!this.closed && this.queue.isEmpty()) {
      this.wait();
    }
    if (this.closed) {
      return null;
    }
    final E element = this.queue.pop();
    this.notifyAll();
    return element;
  }

  /**
   * Transitions the queue into the closed state. All readers (calls to pop) are woken up and return
   * null.
   */
  synchronized void close() {
    this.closed = true;
    this.notifyAll();
  }
}
