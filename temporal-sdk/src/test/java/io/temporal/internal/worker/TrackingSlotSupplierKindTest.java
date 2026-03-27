package io.temporal.internal.worker;

import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.worker.tuning.*;
import org.junit.Test;

public class TrackingSlotSupplierKindTest {

  @Test
  public void testFixedSupplierKind() {
    SlotSupplier<WorkflowSlotInfo> supplier = new FixedSizeSlotSupplier<>(10);
    TrackingSlotSupplier<WorkflowSlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());
    assertEquals("Fixed", tracking.getSupplierKind());
  }

  @Test
  public void testResourceBasedSupplierKind() {
    ResourceBasedController controller =
        ResourceBasedController.newSystemInfoController(
            ResourceBasedControllerOptions.newBuilder(0.5, 0.5).build());
    SlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder().setMinimumSlots(1).setMaximumSlots(10).build());
    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());
    assertEquals("ResourceBased", tracking.getSupplierKind());
  }

  @Test
  public void testCustomSupplierKind() {
    SlotSupplier<WorkflowSlotInfo> custom = new CustomTestSlotSupplier<>();
    TrackingSlotSupplier<WorkflowSlotInfo> tracking =
        new TrackingSlotSupplier<>(custom, new NoopScope());
    assertEquals("CustomTestSlotSupplier", tracking.getSupplierKind());
  }

  private static class CustomTestSlotSupplier<SI extends SlotInfo> implements SlotSupplier<SI> {
    @Override
    public SlotSupplierFuture reserveSlot(SlotReserveContext<SI> ctx) {
      return SlotSupplierFuture.completedFuture(new SlotPermit());
    }

    @Override
    public java.util.Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
      return java.util.Optional.of(new SlotPermit());
    }

    @Override
    public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {}

    @Override
    public void releaseSlot(SlotReleaseContext<SI> ctx) {}

    @Override
    public java.util.Optional<Integer> getMaximumSlots() {
      return java.util.Optional.of(5);
    }
  }
}
