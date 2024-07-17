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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CountingSlotSupplier<SI extends SlotInfo> extends FixedSizeSlotSupplier<SI> {
  public final AtomicInteger reservedCount = new AtomicInteger();
  public final AtomicInteger releasedCount = new AtomicInteger();

  public CountingSlotSupplier(int numSlots) {
    super(numSlots);
  }

  @Override
  public SlotPermit reserveSlot(SlotReserveContext<SI> ctx) throws InterruptedException {
    SlotPermit p = super.reserveSlot(ctx);
    reservedCount.incrementAndGet();
    return p;
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
  public Optional<SlotPermit> tryReserveSlot(
      SlotReserveContext<SI> ctx, long timeout, TimeUnit timeUnit) {
    Optional<SlotPermit> p = super.tryReserveSlot(ctx, timeout, timeUnit);
    if (p.isPresent()) {
      reservedCount.incrementAndGet();
    }
    return p;
  }

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    super.releaseSlot(ctx);
    releasedCount.incrementAndGet();
  }
}
