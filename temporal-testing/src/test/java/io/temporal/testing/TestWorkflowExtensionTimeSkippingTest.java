package io.temporal.testing;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class TestWorkflowExtensionTimeSkippingTest {
  @Test
  public void testCheckNoTimeSkipping() {
    TestWorkflowExtension testWorkflow =
        TestWorkflowExtension.newBuilder().setUseTimeskipping(false).build();

    assertFalse(
        "We disabled the time skipping on the extension, so the TestEnvironmentOptions should have it off too",
        testWorkflow.createTestEnvOptions(System.currentTimeMillis()).isUseTimeskipping());
  }
}
