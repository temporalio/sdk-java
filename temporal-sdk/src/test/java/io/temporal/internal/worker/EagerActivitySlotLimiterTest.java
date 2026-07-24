package io.temporal.internal.worker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EagerActivitySlotLimiterTest {
  @Test
  public void enforcesLimitUntilReservationIsReleased() {
    ActivityWorker.EagerActivitySlotLimiter limiter =
        new ActivityWorker.EagerActivitySlotLimiter(2);

    assertTrue(limiter.tryReserve());
    assertTrue(limiter.tryReserve());
    assertFalse(limiter.tryReserve());

    limiter.release();
    assertTrue(limiter.tryReserve());
  }

  @Test
  public void zeroAllowsUnlimitedReservations() {
    ActivityWorker.EagerActivitySlotLimiter limiter =
        new ActivityWorker.EagerActivitySlotLimiter(0);

    for (int i = 0; i < 1000; i++) {
      assertTrue(limiter.tryReserve());
    }
  }
}
