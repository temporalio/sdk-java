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

package io.temporal.testUtils;

import io.temporal.worker.tuning.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CountingSlotSupplier<SI extends SlotInfo> extends FixedSizeSlotSupplier<SI> {
  public final AtomicInteger reservedCount = new AtomicInteger();
  public final AtomicInteger releasedCount = new AtomicInteger();
  public final AtomicInteger usedCount = new AtomicInteger();
  public final ConcurrentHashMap.KeySetView<SlotPermit, Boolean> currentUsedSet =
      ConcurrentHashMap.newKeySet();

  public CountingSlotSupplier(int numSlots) {
    super(numSlots);
  }

  @Override
  public Future<SlotPermit> reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    Future<SlotPermit> originalFuture = super.reserveSlot(ctx);

    return new Future<SlotPermit>() {
      private final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

      private SlotPermit executeCallbackIfNeeded(SlotPermit permit) {
        if (callbackInvoked.compareAndSet(false, true)) {
          reservedCount.incrementAndGet();
        }
        return permit;
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        return originalFuture.cancel(mayInterruptIfRunning);
      }

      @Override
      public boolean isCancelled() {
        return originalFuture.isCancelled();
      }

      @Override
      public boolean isDone() {
        return originalFuture.isDone();
      }

      @Override
      public SlotPermit get() throws InterruptedException, ExecutionException {
        SlotPermit permit = originalFuture.get();
        return executeCallbackIfNeeded(permit);
      }

      @Override
      public SlotPermit get(long timeout, TimeUnit unit)
          throws InterruptedException, ExecutionException, TimeoutException {
        SlotPermit permit = originalFuture.get(timeout, unit);
        return executeCallbackIfNeeded(permit);
      }
    };
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    Optional<SlotPermit> p = super.tryReserveSlot(ctx);
    if (p.isPresent()) {
      reservedCount.incrementAndGet();
    }
    return p;
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {
    usedCount.incrementAndGet();
    currentUsedSet.add(ctx.getSlotPermit());
    super.markSlotUsed(ctx);
  }

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    super.releaseSlot(ctx);
    currentUsedSet.remove(ctx.getSlotPermit());
    releasedCount.incrementAndGet();
  }
}
