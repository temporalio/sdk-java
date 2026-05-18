package io.temporal.internal.worker;

import org.junit.Test;

public class ShutdownManagerTest {

  @Test(expected = IllegalArgumentException.class)
  public void zeroPeriodIsRejected() {
    new ShutdownManager(0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void negativePeriodIsRejected() {
    new ShutdownManager(-1);
  }
}
