package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.temporal.api.namespace.v1.NamespaceInfo.Capabilities;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import org.junit.Test;

/**
 * Tests for poller-autoscaling auto-enrollment: when a namespace advertises the
 * PollerAutoscalingAutoEnroll capability, poller types left at their default are switched to poller
 * autoscaling, while explicitly-configured poller types are left untouched.
 */
public class PollerAutoscalingAutoEnrollTest {

  private static NamespaceCapabilities capabilities(boolean autoEnroll, boolean pollerAutoscaling) {
    NamespaceCapabilities caps = new NamespaceCapabilities();
    caps.setFromCapabilities(
        Capabilities.newBuilder()
            .setPollerAutoscalingAutoEnroll(autoEnroll)
            .setPollerAutoscaling(pollerAutoscaling)
            .build());
    return caps;
  }

  private static PollerOptions eligibleFixedPollerOptions() {
    return PollerOptions.newBuilder()
        .setPollerBehavior(new PollerBehaviorSimpleMaximum(5))
        .setAutoscalingAutoEnrollEligible(true)
        .build();
  }

  @Test
  public void autoEnrollCapabilityImpliesPollerAutoscaling() {
    // Auto-enroll implies full autoscaling support (including scale-down), so it also enables the
    // pollerAutoscaling flag that PollScaleReportHandle reads as serverSupportsAutoscaling.
    NamespaceCapabilities caps = capabilities(true, false);
    assertTrue(caps.isPollerAutoscalingAutoEnroll());
    assertTrue(caps.isPollerAutoscaling());
  }

  @Test
  public void pollerAutoscalingWithoutAutoEnrollDoesNotImplyAutoEnroll() {
    NamespaceCapabilities caps = capabilities(false, true);
    assertFalse(caps.isPollerAutoscalingAutoEnroll());
    assertTrue(caps.isPollerAutoscaling());
  }

  @Test
  public void noCapabilitiesLeavesEverythingDisabled() {
    NamespaceCapabilities caps = capabilities(false, false);
    assertFalse(caps.isPollerAutoscalingAutoEnroll());
    assertFalse(caps.isPollerAutoscaling());
  }

  @Test
  public void eligibleDefaultIsEnrolledWhenCapabilityAdvertised() {
    PollerOptions resolved =
        PollerOptions.maybeEnrollInPollerAutoscaling(
            eligibleFixedPollerOptions(), capabilities(true, false));
    assertTrue(
        "defaulted poller type should switch to autoscaling",
        resolved.getPollerBehavior() instanceof PollerBehaviorAutoscaling);
    // The enrolled behavior uses the PollerBehaviorAutoscaling defaults.
    assertEquals(new PollerBehaviorAutoscaling(), resolved.getPollerBehavior());
  }

  @Test
  public void eligibleDefaultIsNotEnrolledWhenCapabilityAbsent() {
    PollerOptions options = eligibleFixedPollerOptions();
    PollerOptions resolved =
        PollerOptions.maybeEnrollInPollerAutoscaling(options, capabilities(false, false));
    assertSame("without the capability the options are unchanged", options, resolved);
    assertTrue(resolved.getPollerBehavior() instanceof PollerBehaviorSimpleMaximum);
  }

  @Test
  public void explicitlyConfiguredPollerIsNotEnrolled() {
    // A poller type the user configured explicitly is not eligible and must not be switched, even
    // when the namespace advertises auto-enroll.
    PollerOptions options =
        PollerOptions.newBuilder()
            .setPollerBehavior(new PollerBehaviorSimpleMaximum(3))
            .setAutoscalingAutoEnrollEligible(false)
            .build();
    PollerOptions resolved =
        PollerOptions.maybeEnrollInPollerAutoscaling(options, capabilities(true, false));
    assertSame("explicitly configured poller is untouched", options, resolved);
    assertEquals(
        3,
        ((PollerBehaviorSimpleMaximum) resolved.getPollerBehavior()).getMaxConcurrentTaskPollers());
  }

  @Test
  public void alreadyAutoscalingPollerIsLeftUnchanged() {
    // An eligible poller that already uses autoscaling (e.g. a user-set autoscaling behavior on a
    // type otherwise treated as eligible) is returned unchanged rather than rebuilt.
    PollerBehaviorAutoscaling behavior = new PollerBehaviorAutoscaling(2, 20, 4);
    PollerOptions options =
        PollerOptions.newBuilder()
            .setPollerBehavior(behavior)
            .setAutoscalingAutoEnrollEligible(true)
            .build();
    PollerOptions resolved =
        PollerOptions.maybeEnrollInPollerAutoscaling(options, capabilities(true, false));
    assertSame(options, resolved);
    assertSame(behavior, resolved.getPollerBehavior());
  }
}
