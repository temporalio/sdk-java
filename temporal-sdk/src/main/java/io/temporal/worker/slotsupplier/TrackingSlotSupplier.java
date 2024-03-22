/*
 * Copyright (C) 2024 Temporal Technologies, Inc. All Rights Reserved.
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

package io.temporal.worker.slotsupplier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: Also make pauseable?
public class TrackingSlotSupplier<SlotInfo> implements SlotSupplier<SlotInfo> {
  private final SlotSupplier<SlotInfo> inner;
  private final AtomicInteger issuedSlots = new AtomicInteger();
  private final Map<SlotPermit, SlotInfo> usedSlots = new HashMap<>();

  public TrackingSlotSupplier(SlotSupplier<SlotInfo> inner) {
    this.inner = inner;
  }

  @Override
  public SlotPermit reserveSlot(SlotReservationContext ctx) throws InterruptedException {
    SlotPermit p = inner.reserveSlot(ctx);
    issuedSlots.incrementAndGet();
    return p;
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReservationContext ctx) {
    Optional<SlotPermit> p = inner.tryReserveSlot(ctx);
    if (p.isPresent()) {
      issuedSlots.incrementAndGet();
    }
    return p;
  }

  @Override
  public void markSlotUsed(SlotInfo slotInfo, SlotPermit permit) {
    inner.markSlotUsed(slotInfo, permit);
    usedSlots.put(permit, slotInfo);
  }

  @Override
  public void releaseSlot(SlotReleaseReason reason, SlotPermit permit) {
    inner.releaseSlot(reason, permit);
    issuedSlots.decrementAndGet();
    usedSlots.remove(permit);
  }

  @Override
  public int maximumSlots() {
    return inner.maximumSlots();
  }

  public int getIssuedSlots() {
    return issuedSlots.get();
  }
}
