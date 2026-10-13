package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import io.temporal.activity.ActivityOptions;
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

  @Test
  public void copiesActivityOptionsOverrideWithoutInvokingIt() {
    Functions.Proc1<ActivityOptions.Builder> override =
        b -> {
          throw new AssertionError("Copying invocation options must not run the override.");
        };
    ActivityInvocationOptions original =
        ActivityInvocationOptions.newBuilder()
            .setActivityId("activity-123")
            .setActivityOptionsOverride(override)
            .build();
    ActivityInvocationOptions copy = original.toBuilder().build();
    assertSame(override, copy.getActivityOptionsOverride());
    assertEquals(original, copy);
    assertEquals(original.hashCode(), copy.hashCode());
    assertSame(
        override,
        ActivityInvocationOptions.newBuilder(original).build().getActivityOptionsOverride());
    assertNull(ActivityInvocationOptions.getDefaultInstance().getActivityOptionsOverride());
    assertThrows(
        NullPointerException.class,
        () -> ActivityInvocationOptions.newBuilder().setActivityOptionsOverride(null));
  }
}
