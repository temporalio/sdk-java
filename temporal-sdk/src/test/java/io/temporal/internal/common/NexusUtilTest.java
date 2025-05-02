package io.temporal.internal.common;

import org.junit.Assert;
import org.junit.Test;

public class NexusUtilTest {
  @Test
  public void testParseRequestTimeout() {
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NexusUtil.parseRequestTimeout("invalid"));
    Assert.assertThrows(IllegalArgumentException.class, () -> NexusUtil.parseRequestTimeout("1h"));
    Assert.assertEquals(java.time.Duration.ofMillis(10), NexusUtil.parseRequestTimeout("10ms"));
    Assert.assertEquals(java.time.Duration.ofMillis(10), NexusUtil.parseRequestTimeout("10.1ms"));
    Assert.assertEquals(java.time.Duration.ofSeconds(1), NexusUtil.parseRequestTimeout("1s"));
    Assert.assertEquals(java.time.Duration.ofMinutes(999), NexusUtil.parseRequestTimeout("999m"));
    Assert.assertEquals(java.time.Duration.ofMillis(1300), NexusUtil.parseRequestTimeout("1.3s"));
  }
}
