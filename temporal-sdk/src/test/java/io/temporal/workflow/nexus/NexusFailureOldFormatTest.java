package io.temporal.workflow.nexus;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Runs the OperationFailMetric suite with the old failure format forced via system property. This
 * verifies that the test server correctly handles the old format (UnsuccessfulOperationError and
 * HandlerError) even though it advertises support for the new format.
 *
 * <p>The system property "temporal.nexus.forceOldFailureFormat=true" makes the SDK send old format
 * responses regardless of server capabilities.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({OperationFailMetricTest.class})
public class NexusFailureOldFormatTest {
  private static String originalValue;

  @BeforeClass
  public static void setUpClass() {
    // Save original value if it exists
    originalValue = System.getProperty("temporal.nexus.forceOldFailureFormat");
    // Force old format for all tests in this suite
    System.setProperty("temporal.nexus.forceOldFailureFormat", "true");
  }

  @AfterClass
  public static void tearDownClass() {
    // Restore original value
    if (originalValue != null) {
      System.setProperty("temporal.nexus.forceOldFailureFormat", originalValue);
    } else {
      System.clearProperty("temporal.nexus.forceOldFailureFormat");
    }
  }
}
