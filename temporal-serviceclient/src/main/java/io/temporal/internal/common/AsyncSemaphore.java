package io.temporal.internal.common;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A simple async semaphore. Unfortunately there's not any readily available properly licensed
 * library I could find for this which is a bit shocking, but this implementation should be suitable
 * for our needs.
 */
public final class AsyncSemaphore {
  private final ReentrantLock lock = new ReentrantLock();
  private final Queue<CompletableFuture<Void>> waiters = new ArrayDeque<>();
  private int permits;

  public AsyncSemaphore(int initialPermits) {
    this.permits = initialPermits;
  }

  /**
   * Acquire a permit asynchronously. If a permit is available, returns a completed future,
   * otherwise returns a future that will be completed when a permit is released.
   */
  public CompletableFuture<Void> acquire() {
    lock.lock();
    try {
      if (permits > 0) {
        permits--;
        return CompletableFuture.completedFuture(null);
      } else {
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        waiters.add(waiter);
        return waiter;
      }
    } finally {
      lock.unlock();
    }
  }

  public boolean tryAcquire() {
    lock.lock();
    try {
      if (permits > 0) {
        permits--;
        return true;
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Release a permit. If there are waiting futures, completes the next one instead of incrementing
   * the permit count.
   */
  public void release() {
    lock.lock();
    try {
      CompletableFuture<Void> waiter = waiters.poll();
      if (waiter != null) {
        if (!waiter.complete(null) && waiter.isCancelled()) {
          // If this waiter was cancelled, we need to release another permit, since this waiter
          // is now useless
          release();
        }
      } else {
        permits++;
      }
    } finally {
      lock.unlock();
    }
  }
}
