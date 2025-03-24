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

package io.temporal.internal.testservice;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Priority;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;

/**
 * A specialized unbounded queue that requires blocking poll operations to happen through a Future
 * so that they can be cancelled (i.e. cancelling the future breaks out of the poll via a
 * j.u.c.CancellationException).
 *
 * @param <E>
 */
class TaskQueue<E> {
  private static class TaskQueueElement<E> {
    private final E value;
    private final Priority priority;

    TaskQueueElement(E value, Priority priority) {
      this.value = value;
      // TODO(Quinn): make this configurable
      this.priority =
          priority == Priority.getDefaultInstance()
              ? Priority.newBuilder().setPriorityKey(3).build()
              : priority;
    }

    TaskQueueElement(E value) {
      this.value = value;
      this.priority = Priority.newBuilder().setPriorityKey(3).build();
    }

    public E getValue() {
      return value;
    }

    public Priority getPriority() {
      return priority;
    }
  }

  private final PriorityQueue<TaskQueueElement<E>> backlog =
      new PriorityQueue<>(Comparator.comparingInt(o -> o.getPriority().getPriorityKey()));
  private final LinkedList<PollFuture> waiters = new LinkedList<>();

  /**
   * Adds the provided element to the queue at the default priority.
   *
   * @param element the value to add
   */
  synchronized void add(E element) {
    for (PollFuture future = waiters.poll(); future != null; future = waiters.pop()) {
      if (future.set(element)) {
        return;
      }
    }
    backlog.add(new TaskQueueElement(element));
  }

  /**
   * Adds the provided element to the queue at the given priority.
   *
   * @param element the value to add
   * @param priority the priority of the element
   */
  synchronized void add(E element, Priority priority) {
    for (PollFuture future = waiters.poll(); future != null; future = waiters.pop()) {
      if (future.set(element)) {
        return;
      }
    }
    backlog.add(new TaskQueueElement(element, priority));
  }

  /**
   * Creates a new j.u.c.Future whose get() method will eventually return a value from the head of
   * this queue. Note that failing to call get() on the returned Future can result in missed queue
   * updates.
   *
   * @return a Future providing one-shot access to the head of this queue.
   */
  synchronized Future<E> poll() {
    final PollFuture future = new PollFuture();
    E element;
    synchronized (this) {
      if (backlog.isEmpty()) {
        waiters.push(future);
        return future;
      }
      element = backlog.poll().getValue();
    }
    future.set(element);
    return future;
  }

  /**
   * A Future implementation specifically for consuming from the enclosing TaskQueue type. The get
   * method on this class blocks until a value is available from the queue but unlike
   * BlockingQueue#take, a blocked consumer can be "interrupted" without the use of thread
   * interruption by calling #cancel() on this Future.
   */
  private class PollFuture implements Future<E> {
    boolean cancelled = false;
    E value;

    private synchronized boolean set(E element) {
      Preconditions.checkState(value == null);
      if (cancelled) {
        return false;
      }
      value = element;
      notifyAll();
      return true;
    }

    @Override
    public boolean cancel(boolean ignored) {
      synchronized (TaskQueue.this) {
        TaskQueue.this.waiters.remove(this);
      }
      synchronized (this) {
        if (value != null) {
          return false;
        }
        cancelled = true;
        notifyAll();
        return true;
      }
    }

    @Override
    public synchronized boolean isCancelled() {
      return cancelled;
    }

    @Override
    public synchronized boolean isDone() {
      return value != null;
    }

    @Override
    public synchronized E get() throws InterruptedException, ExecutionException {
      while (value == null && !cancelled) {
        this.wait();
      }
      if (cancelled) {
        throw new CancellationException();
      }
      return value;
    }

    @Override
    public synchronized E get(long timeout, @Nonnull TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      while (value == null && !cancelled) {
        unit.timedWait(this, timeout);
      }
      if (cancelled) {
        throw new CancellationException();
      }
      return value;
    }
  }
}
