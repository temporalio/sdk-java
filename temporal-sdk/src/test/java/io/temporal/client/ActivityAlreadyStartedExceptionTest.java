package io.temporal.client;

import static org.junit.Assert.*;

import org.junit.Test;

public class ActivityAlreadyStartedExceptionTest {

  @Test
  public void testMessageContainsKeyInfo() {
    ActivityAlreadyStartedException ex =
        new ActivityAlreadyStartedException("my-act", "DoWork", "r1", new RuntimeException());
    String msg = ex.getMessage();
    assertNotNull(msg);
    assertTrue("message should contain activityId", msg.contains("my-act"));
    assertTrue("message should contain activityType", msg.contains("DoWork"));
  }
}
