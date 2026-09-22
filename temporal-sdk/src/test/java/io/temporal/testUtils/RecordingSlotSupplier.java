package io.temporal.testUtils;

import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.SlotInfo;
import io.temporal.worker.tuning.SlotMarkUsedContext;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseContext;
import io.temporal.worker.tuning.SlotReserveContext;
import io.temporal.worker.tuning.SlotSupplierFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A fixed-size slot supplier that records slot usage for tests. */
public final class RecordingSlotSupplier<SI extends SlotInfo> extends FixedSizeSlotSupplier<SI> {
  private final ConcurrentLinkedQueue<SlotPermit> reservedPermits = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<SlotMarkUsedContext<SI>> markUsedContexts =
      new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<SlotReleaseContext<SI>> releaseContexts =
      new ConcurrentLinkedQueue<>();

  public RecordingSlotSupplier(int numSlots) {
    super(numSlots);
  }

  @Override
  public SlotSupplierFuture reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    SlotSupplierFuture future = super.reserveSlot(ctx);
    future.thenAccept(reservedPermits::add);
    return future;
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    Optional<SlotPermit> permit = super.tryReserveSlot(ctx);
    permit.ifPresent(reservedPermits::add);
    return permit;
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {
    markUsedContexts.add(ctx);
    super.markSlotUsed(ctx);
  }

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {
    releaseContexts.add(ctx);
    super.releaseSlot(ctx);
  }

  public List<SlotMarkUsedContext<SI>> getMarkUsedContexts() {
    return new ArrayList<>(markUsedContexts);
  }

  public List<SlotPermit> getReservedPermits() {
    return new ArrayList<>(reservedPermits);
  }

  public List<SlotReleaseContext<SI>> getReleaseContexts() {
    return new ArrayList<>(releaseContexts);
  }
}
