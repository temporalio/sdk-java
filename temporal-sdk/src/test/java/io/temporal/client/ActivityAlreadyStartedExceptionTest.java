package io.temporal.client;

import static org.junit.Assert.*;

import org.junit.Test;

public class ActivityAlreadyStartedExceptionTest {

  @Test
  public void testFieldsWithRunId() {
    RuntimeException cause = new RuntimeException("grpc cause");
    ActivityAlreadyStartedException ex =
        new ActivityAlreadyStartedException("act-id", "MyActivity", "run-123", cause);

    assertEquals("act-id", ex.getActivityId());
    assertEquals("MyActivity", ex.getActivityType());
    assertEquals("run-123", ex.getRunId());
    assertSame(cause, ex.getCause());
  }

  @Test
  public void testFieldsWithNullRunId() {
    RuntimeException cause = new RuntimeException("grpc cause");
    ActivityAlreadyStartedException ex =
        new ActivityAlreadyStartedException("act-id", "MyActivity", null, cause);

    assertEquals("act-id", ex.getActivityId());
    assertEquals("MyActivity", ex.getActivityType());
    assertNull(ex.getRunId());
  }

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
