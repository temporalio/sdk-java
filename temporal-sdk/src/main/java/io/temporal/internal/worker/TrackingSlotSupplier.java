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
import io.temporal.worker.tuning.*;
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
public class TrackingSlotSupplier<SI extends SlotInfo> {
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
    inner.markSlotUsed(new SlotMarkUsedContextImpl(slotInfo, permit));
    usedSlots.put(permit, slotInfo);
    publishSlotsMetric();
  }

  public void releaseSlot(SlotReleaseReason reason, SlotPermit permit) {
    if (permit == null) {
      return;
    }
    SI slotInfo = usedSlots.get(permit);
    inner.releaseSlot(new SlotReleaseContextImpl(reason, permit, slotInfo));
    issuedSlots.decrementAndGet();
    usedSlots.remove(permit);
    publishSlotsMetric();
  }

  public int maximumSlots() {
    return inner.getMaximumSlots();
  }

  public int getIssuedSlots() {
    return issuedSlots.get();
  }

  public void setMetricsScope(Scope metricsScope) {
    this.metricsScope = metricsScope;
  }

  /**
   * If any slot supplier is resource-based, we want to attach a metrics scope to the controller
   * (before it's labelled with the worker type).
   */
  public void attachMetricsToResourceController(Scope metricsScope) {
    if (inner instanceof ResourceBasedSlotSupplier) {
      ((ResourceBasedSlotSupplier<?>) inner).getResourceController().setMetricsScope(metricsScope);
    }
  }

  Map<SlotPermit, SI> getUsedSlots() {
    return usedSlots;
  }

  private void publishSlotsMetric() {
    this.metricsScope
        .gauge(MetricsType.WORKER_TASK_SLOTS_AVAILABLE)
        .update(maximumSlots() - usedSlots.size());
  }

  private SlotReserveContext<SI> createCtx(SlotReservationData dat) {
    return new SlotReserveContextImpl(
        dat.taskQueue,
        Collections.unmodifiableMap(usedSlots),
        dat.workerIdentity,
        dat.workerBuildId,
        issuedSlots);
  }

  private class SlotReserveContextImpl implements SlotReserveContext<SI> {
    private final String taskQueue;
    private final Map<SlotPermit, SI> usedSlots;
    private final String workerIdentity;
    private final String workerBuildId;
    private final AtomicInteger issuedSlots;

    private SlotReserveContextImpl(
        String taskQueue,
        Map<SlotPermit, SI> usedSlots,
        String workerIdentity,
        String workerBuildId,
        AtomicInteger issuedSlots) {
      this.taskQueue = taskQueue;
      this.usedSlots = usedSlots;
      this.workerIdentity = workerIdentity;
      this.workerBuildId = workerBuildId;
      this.issuedSlots = issuedSlots;
    }

    @Override
    public String getTaskQueue() {
      return taskQueue;
    }

    @Override
    public Map<SlotPermit, SI> getUsedSlots() {
      return usedSlots;
    }

    @Override
    public String getWorkerIdentity() {
      return workerIdentity;
    }

    @Override
    public String getWorkerBuildId() {
      return workerBuildId;
    }

    @Override
    public int getNumIssuedSlots() {
      return issuedSlots.get();
    }
  }

  private class SlotMarkUsedContextImpl implements SlotMarkUsedContext<SI> {
    private final SI slotInfo;
    private final SlotPermit slotPermit;

    protected SlotMarkUsedContextImpl(SI slotInfo, SlotPermit slotPermit) {
      this.slotInfo = slotInfo;
      this.slotPermit = slotPermit;
    }

    @Override
    public SI getSlotInfo() {
      return slotInfo;
    }

    @Override
    public SlotPermit getSlotPermit() {
      return slotPermit;
    }
  }

  private class SlotReleaseContextImpl implements SlotReleaseContext<SI> {
    private final SlotPermit slotPermit;
    private final SlotReleaseReason reason;
    private final SI slotInfo;

    protected SlotReleaseContextImpl(SlotReleaseReason reason, SlotPermit slotPermit, SI slotInfo) {
      this.slotPermit = slotPermit;
      this.reason = reason;
      this.slotInfo = slotInfo;
    }

    @Override
    public SlotReleaseReason getSlotReleaseReason() {
      return reason;
    }

    @Override
    public SlotPermit getSlotPermit() {
      return slotPermit;
    }

    @Override
    public SI getSlotInfo() {
      return slotInfo;
    }
  }
}
