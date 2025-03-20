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

package io.temporal.worker.tuning;

import com.google.common.base.Preconditions;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This implementation of {@link SlotSupplier} provides a fixed number of slots backed by a
 * semaphore, and is the default behavior when a custom supplier is not explicitly specified.
 *
 * @param <SI> The slot info type for this supplier.
 */
public class FixedSizeSlotSupplier<SI extends SlotInfo> implements SlotSupplier<SI> {
  private final int numSlots;
  private final AsyncSemaphore executorSlotsSemaphore;

  /**
   * A simple version of an async semaphore. Unfortunately there's not any readily available
   * properly licensed library I could find for this which is a bit shocking, but this
   * implementation should be suitable for our needs
   */
  static class AsyncSemaphore {
    private final ReentrantLock lock = new ReentrantLock();
    private final Queue<CompletableFuture<Void>> waiters = new ArrayDeque<>();
    private int permits;

    AsyncSemaphore(int initialPermits) {
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
     * Release a permit. If there are waiting futures, completes the next one instead of
     * incrementing the permit count.
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

  public FixedSizeSlotSupplier(int numSlots) {
    Preconditions.checkArgument(numSlots > 0, "FixedSizeSlotSupplier must have at least one slot");
    this.numSlots = numSlots;
    executorSlotsSemaphore = new AsyncSemaphore(numSlots);
  }

  @Override
  public SlotSupplierFuture reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    CompletableFuture<Void> slotFuture = executorSlotsSemaphore.acquire();
    return SlotSupplierFuture.fromCompletableFuture(
        slotFuture.thenApply(ignored -> new SlotPermit()), () -> slotFuture.cancel(true));
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    boolean gotOne = executorSlotsSemaphore.tryAcquire();
    if (gotOne) {
      return Optional.of(new SlotPermit());
    }
    return Optional.empty();
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {}

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    executorSlotsSemaphore.release();
  }

  @Override
  public Optional<Integer> getMaximumSlots() {
    return Optional.of(numSlots);
  }

  @Override
  public String toString() {
    return "FixedSizeSlotSupplier{" + "numSlots=" + numSlots + '}';
  }
}
