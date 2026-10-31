package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ActivityInvocationOptionsTest {

  @Test
  public void setActivityIdRejectsNull() {
    NullPointerException e =
        assertThrows(
            NullPointerException.class,
            () -> ActivityInvocationOptions.newBuilder().setActivityId(null));
    assertEquals("activityId", e.getMessage());
  }

  @Test
  public void setActivityIdRejectsEmpty() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ActivityInvocationOptions.newBuilder().setActivityId(""));
    assertEquals("activityId must not be empty", e.getMessage());
  }

  @Test
  public void newBuilderCopiesActivityId() {
    ActivityInvocationOptions original =
        ActivityInvocationOptions.newBuilder().setActivityId("activity-123").build();

    ActivityInvocationOptions copy = ActivityInvocationOptions.newBuilder(original).build();

    assertEquals("activity-123", copy.getActivityId());
  }
}
