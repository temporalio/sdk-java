package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.*;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a slot supplier and supplements it with additional tracking information that is useful to
 * provide to all implementations. This type is used internally rather than {@link SlotSupplier}
 * directly.
 *
 * @param <SI> The slot info type
 */
public class TrackingSlotSupplier<SI extends SlotInfo> {
  private static final Logger log = LoggerFactory.getLogger(TrackingSlotSupplier.class);
  private final SlotSupplier<SI> inner;
  private final AtomicInteger issuedSlots = new AtomicInteger();
  private final Map<SlotPermit, SI> usedSlots = new ConcurrentHashMap<>();
  private final Scope metricsScope;

  public TrackingSlotSupplier(SlotSupplier<SI> inner, Scope metricsScope) {
    this.inner = inner;
    this.metricsScope = metricsScope;
    publishSlotsMetric();
  }

  public SlotSupplierFuture reserveSlot(SlotReservationData data) {
    final SlotSupplierFuture future;
    try {
      future = inner.reserveSlot(createCtx(data));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    future.thenRun(issuedSlots::incrementAndGet);
    return future;
  }

  public Optional<SlotPermit> tryReserveSlot(SlotReservationData data) {
    Optional<SlotPermit> p = inner.tryReserveSlot(createCtx(data));
    if (p.isPresent()) {
      issuedSlots.incrementAndGet();
    }
    return p;
  }

  public void markSlotUsed(SI slotInfo, SlotPermit permit) {
    if (permit == null) {
      throw new IllegalArgumentException(
          "Permit cannot be null when marking slot as used. This is an SDK bug.");
    }
    if (usedSlots.put(permit, slotInfo) != null) {
      throw new IllegalStateException("Slot is being marked used twice. This is an SDK bug.");
    }
    inner.markSlotUsed(new SlotMarkUsedContextImpl(slotInfo, permit));
    publishSlotsMetric();
  }

  public void releaseSlot(SlotReleaseReason reason, SlotPermit permit) {
    if (permit == null) {
      throw new IllegalArgumentException(
          "Permit cannot be null when releasing a slot. This is an SDK bug.");
    }
    SI slotInfo = usedSlots.get(permit);
    inner.releaseSlot(new SlotReleaseContextImpl(reason, permit, slotInfo));
    issuedSlots.decrementAndGet();
    usedSlots.remove(permit);
    publishSlotsMetric();
  }

  public Optional<Integer> maximumSlots() {
    return inner.getMaximumSlots();
  }

  public int getIssuedSlots() {
    return issuedSlots.get();
  }

  Map<SlotPermit, SI> getUsedSlots() {
    return usedSlots;
  }

  private void publishSlotsMetric() {
    if (maximumSlots().isPresent()) {
      this.metricsScope
          .gauge(MetricsType.WORKER_TASK_SLOTS_AVAILABLE)
          .update(maximumSlots().get() - usedSlots.size());
    }
    this.metricsScope.gauge(MetricsType.WORKER_TASK_SLOTS_USED).update(usedSlots.size());
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
