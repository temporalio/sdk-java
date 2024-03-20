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

package io.temporal.internal.worker;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.worker.tuning.SlotReservationContext;
import io.temporal.worker.tuning.SlotSupplier;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a slot supplier and supplements it with additional tracking information that is useful to
 * provide to all implementations. This type is used internally rather than {@link SlotSupplier}
 * directly.
 *
 * @param <SI> The slot info type
 */
public class TrackingSlotSupplier<SI> {
  private final SlotSupplier<SI> inner;
  private final AtomicInteger issuedSlots = new AtomicInteger();
  private final Map<SlotPermit, SI> usedSlots = new ConcurrentHashMap<>();
  private Scope metricsScope;

  public TrackingSlotSupplier(SlotSupplier<SI> inner) {
    this.inner = inner;
    metricsScope = new NoopScope();
  }

  public SlotPermit reserveSlot(SlotReservationData dat) throws InterruptedException {
    SlotPermit p = inner.reserveSlot(createCtx(dat));
    issuedSlots.incrementAndGet();
    return p;
  }

  public Optional<SlotPermit> tryReserveSlot(SlotReservationData dat) {
    Optional<SlotPermit> p = inner.tryReserveSlot(createCtx(dat));
    if (p.isPresent()) {
      issuedSlots.incrementAndGet();
    }
    return p;
  }

  public void markSlotUsed(SI slotInfo, SlotPermit permit) {
    if (permit == null) {
      return;
    }
    inner.markSlotUsed(slotInfo, permit);
    usedSlots.put(permit, slotInfo);
    publishSlotsMetric();
  }

  public void releaseSlot(SlotReleaseReason reason, SlotPermit permit) {
    if (permit == null) {
      return;
    }
    inner.releaseSlot(reason, permit);
    issuedSlots.decrementAndGet();
    usedSlots.remove(permit);
    publishSlotsMetric();
  }

  public int maximumSlots() {
    return inner.maximumSlots();
  }

  public int getIssuedSlots() {
    return issuedSlots.get();
  }

  public void setMetricsScope(Scope metricsScope) {
    this.metricsScope = metricsScope;
  }

  private void publishSlotsMetric() {
    this.metricsScope
        .gauge(MetricsType.WORKER_TASK_SLOTS_AVAILABLE)
        .update(maximumSlots() - usedSlots.size());
  }

  private SlotReservationContext<SI> createCtx(SlotReservationData dat) {
    return new SlotResCtx(
        dat.getTaskQueue(), dat.isSticky(), Collections.unmodifiableMap(usedSlots));
  }

  private class SlotResCtx implements SlotReservationContext<SI> {
    private final String taskQueue;
    private final boolean sticky;
    private final Map<SlotPermit, SI> usedSlots;

    private SlotResCtx(String taskQueue, boolean sticky, Map<SlotPermit, SI> usedSlots) {
      this.taskQueue = taskQueue;
      this.sticky = sticky;
      this.usedSlots = usedSlots;
    }

    @Override
    public String getTaskQueue() {
      return taskQueue;
    }

    @Override
    public boolean isSticky() {
      return sticky;
    }

    @Override
    public Map<SlotPermit, SI> usedSlots() {
      return usedSlots;
    }
  }
}
