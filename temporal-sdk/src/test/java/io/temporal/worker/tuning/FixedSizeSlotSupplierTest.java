package io.temporal.worker.tuning;

import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.internal.worker.SlotReservationData;
import io.temporal.internal.worker.TrackingSlotSupplier;
import org.junit.Test;

public class FixedSizeSlotSupplierTest {

  @Test
  public void ensureInterruptingReservationWorksWhenWaitingOnSemaphoreInQueue() throws Exception {
    FixedSizeSlotSupplier<WorkflowSlotInfo> supplier = new FixedSizeSlotSupplier<>(1);
    TrackingSlotSupplier<WorkflowSlotInfo> trackingSS =
        new TrackingSlotSupplier<>(supplier, new NoopScope());
    // Reserve one slot and don't release
    SlotPermit firstPermit =
        trackingSS.reserveSlot(new SlotReservationData("bla", "blah", "bla")).get();

    // Try to reserve another slot
    SlotSupplierFuture secondSlotFuture =
        trackingSS.reserveSlot(new SlotReservationData("bla", "blah", "bla"));
    // Try to reserve a third
    SlotSupplierFuture thirdSlotFuture =
        trackingSS.reserveSlot(new SlotReservationData("bla", "blah", "bla"));

    // Cancel second reservation & release first permit, which should allow third to be acquired
    SlotPermit maybePermit = secondSlotFuture.abortReservation();
    assertNull(maybePermit);
    trackingSS.releaseSlot(SlotReleaseReason.neverUsed(), firstPermit);

    SlotPermit p = thirdSlotFuture.get();
    assertNotNull(p);
  }
}
