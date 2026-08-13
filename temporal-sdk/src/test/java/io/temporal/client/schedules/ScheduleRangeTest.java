package io.temporal.client.schedules;

import org.junit.Assert;
import org.junit.Test;

public class ScheduleRangeTest {
  @Test
  public void rejectsNegativeEnd() {
    Assert.assertThrows(IllegalStateException.class, () -> new ScheduleRange(0, -1));
    Assert.assertThrows(IllegalStateException.class, () -> new ScheduleRange(0, -1, 0));
  }
}
