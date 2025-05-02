package io.temporal.testing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestWorkflowRuleTimeSkippingTest {

  @Test
  public void testWorkflowRuleTimeSkipping() {
    TestWorkflowRule defaultTestWorkflowRule = TestWorkflowRule.newBuilder().build();
    TestWorkflowRule noTimeSkippingWorkflowRule =
        TestWorkflowRule.newBuilder().setUseTimeskipping(false).build();

    assertTrue(
        "By default time skipping should be on",
        defaultTestWorkflowRule
            .createTestEnvOptions(System.currentTimeMillis())
            .isUseTimeskipping());
    assertFalse(
        "We disabled the time skipping on the rule, so the TestEnvironmentOptions should have it off too",
        noTimeSkippingWorkflowRule
            .createTestEnvOptions(System.currentTimeMillis())
            .isUseTimeskipping());
  }
}
