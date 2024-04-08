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
import java.util.Optional;
import java.util.concurrent.*;

/**
 * This implementation of {@link SlotSupplier} provides a fixed number of slots backed by a
 * semaphore, and is the default behavior when a custom supplier is not explicitly specified.
 *
 * @param <SI> The slot info type for this supplier.
 */
public class FixedSizeSlotSupplier<SI extends SlotInfo> implements SlotSupplier<SI> {
  private final int numSlots;
  private final Semaphore executorSlotsSemaphore;

  public FixedSizeSlotSupplier(int numSlots) {
    Preconditions.checkArgument(numSlots > 0, "FixedSizeSlotSupplier must have at least one slot");
    this.numSlots = numSlots;
    executorSlotsSemaphore = new Semaphore(numSlots);
  }

  @Override
  public SlotPermit reserveSlot(SlotReserveContext<SI> ctx) throws InterruptedException {
    executorSlotsSemaphore.acquire();
    return new SlotPermit();
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
  public int maximumSlots() {
    return numSlots;
  }

  @Override
  public String toString() {
    return "FixedSizeSlotSupplier{" + "numSlots=" + numSlots + '}';
  }
}
