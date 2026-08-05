package io.temporal.internal.client;

import io.temporal.client.schedules.SchedulePolicy;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Test;

public class ScheduleProtoUtilTest {
  private final ScheduleProtoUtil util = new ScheduleProtoUtil(null, null);

  @Test
  public void policyToProtoOmitsDefaultCatchupWindow() {
    Assert.assertFalse(util.policyToProto(SchedulePolicy.newBuilder().build()).hasCatchupWindow());
  }

  @Test
  public void policyToProtoIncludesExplicitCatchupWindow() {
    Assert.assertEquals(
        300L,
        util.policyToProto(
                SchedulePolicy.newBuilder().setCatchupWindow(Duration.ofMinutes(5)).build())
            .getCatchupWindow()
            .getSeconds());
  }
}
