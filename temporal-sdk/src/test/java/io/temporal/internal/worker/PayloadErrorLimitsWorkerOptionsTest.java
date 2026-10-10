package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.internal.payload.limits.PayloadErrorLimits;
import org.junit.Test;

public class PayloadErrorLimitsWorkerOptionsTest {

  @Test
  public void theWorkerAttachesTheNamespaceErrorLimits() {
    NamespaceCapabilities capabilities = limits(2_000, 3_000);
    PayloadErrorLimits limits =
        SingleWorkerOptions.newBuilder().build().payloadErrorLimits(capabilities);
    assertEquals(2_000, limits.getBlob());
    assertEquals(3_000, limits.getMemo());
    assertSame(capabilities.getPayloadErrorLimits(), limits);
  }

  @Test
  public void disablingEnforcementAttachesNoErrorLimits() {
    assertNull(
        SingleWorkerOptions.newBuilder()
            .setPayloadErrorLimitDisabled(true)
            .build()
            .payloadErrorLimits(limits(2_000, 3_000)));
  }

  @Test
  public void aNamespaceWithoutErrorLimitsAttachesNone() {
    assertNull(new NamespaceCapabilities().getPayloadErrorLimits());
    assertNull(limits(0, 0).getPayloadErrorLimits());
  }

  @Test
  public void negativeErrorLimitsDisableThatLimit() {
    PayloadErrorLimits limits = limits(-1, 3_000).getPayloadErrorLimits();
    assertEquals(0, limits.getBlob());
    assertEquals(3_000, limits.getMemo());
  }

  private static NamespaceCapabilities limits(long blob, long memo) {
    NamespaceCapabilities capabilities = new NamespaceCapabilities();
    capabilities.setFromLimits(
        NamespaceInfo.Limits.newBuilder()
            .setBlobSizeLimitError(blob)
            .setMemoSizeLimitError(memo)
            .build());
    return capabilities;
  }
}
