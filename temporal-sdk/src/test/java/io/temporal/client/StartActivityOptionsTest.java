package io.temporal.client;

import static org.junit.Assert.*;

import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.api.enums.v1.ActivityIdReusePolicy;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import org.junit.Test;

public class StartActivityOptionsTest {

  @Test
  public void testMinimalValid() {
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId("my-activity")
            .setTaskQueue("my-queue")
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build();
    assertEquals("my-activity", opts.getId());
    assertEquals("my-queue", opts.getTaskQueue());
    assertEquals(Duration.ofSeconds(30), opts.getStartToCloseTimeout());
    assertNull(opts.getScheduleToCloseTimeout());
    assertEquals(
        ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_ALLOW_DUPLICATE, opts.getIdReusePolicy());
    assertEquals(
        ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_UNSPECIFIED,
        opts.getIdConflictPolicy());
  }

  @Test
  public void testWithScheduleToCloseTimeout() {
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId("act")
            .setTaskQueue("q")
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .build();
    assertEquals(Duration.ofMinutes(5), opts.getScheduleToCloseTimeout());
    assertNull(opts.getStartToCloseTimeout());
  }

  @Test
  public void testFullOptions() {
    RetryOptions retry = RetryOptions.newBuilder().setMaximumAttempts(3).build();
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId("act-id")
            .setTaskQueue("task-queue")
            .setScheduleToCloseTimeout(Duration.ofMinutes(10))
            .setScheduleToStartTimeout(Duration.ofSeconds(30))
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setHeartbeatTimeout(Duration.ofSeconds(10))
            .setIdReusePolicy(ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL)
            .setRetryOptions(retry)
            .setStaticSummary("summary")
            .setStaticDetails("details")
            .build();

    assertEquals("act-id", opts.getId());
    assertEquals("task-queue", opts.getTaskQueue());
    assertEquals(Duration.ofMinutes(10), opts.getScheduleToCloseTimeout());
    assertEquals(Duration.ofSeconds(30), opts.getScheduleToStartTimeout());
    assertEquals(Duration.ofMinutes(5), opts.getStartToCloseTimeout());
    assertEquals(Duration.ofSeconds(10), opts.getHeartbeatTimeout());
    assertEquals(
        ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE, opts.getIdReusePolicy());
    assertEquals(
        ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL, opts.getIdConflictPolicy());
    assertEquals(retry, opts.getRetryOptions());
    assertEquals("summary", opts.getStaticSummary());
    assertEquals("details", opts.getStaticDetails());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMissingIdFails() {
    StartActivityOptions.newBuilder()
        .setTaskQueue("q")
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMissingTaskQueueFails() {
    StartActivityOptions.newBuilder()
        .setId("id")
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMissingTimeoutFails() {
    StartActivityOptions.newBuilder().setId("id").setTaskQueue("q").build();
  }

  @Test
  public void testToBuilder() {
    StartActivityOptions original =
        StartActivityOptions.newBuilder()
            .setId("orig")
            .setTaskQueue("q")
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .build();
    StartActivityOptions copy = original.toBuilder().setId("copy").build();
    assertEquals("copy", copy.getId());
    assertEquals("q", copy.getTaskQueue());
    assertEquals(Duration.ofSeconds(5), copy.getStartToCloseTimeout());
  }

  @Test
  public void testToBuilderPreservesAllFields() {
    RetryOptions retry = RetryOptions.newBuilder().setMaximumAttempts(5).build();
    Priority priority = Priority.newBuilder().setPriorityKey(2).build();
    StartActivityOptions original =
        StartActivityOptions.newBuilder()
            .setId("act")
            .setTaskQueue("tq")
            .setScheduleToCloseTimeout(Duration.ofMinutes(10))
            .setScheduleToStartTimeout(Duration.ofSeconds(30))
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setHeartbeatTimeout(Duration.ofSeconds(15))
            .setIdReusePolicy(ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL)
            .setRetryOptions(retry)
            .setStaticSummary("summary")
            .setStaticDetails("details")
            .setPriority(priority)
            .build();

    StartActivityOptions copy = original.toBuilder().build();

    assertEquals(original, copy);
    assertEquals(retry, copy.getRetryOptions());
    assertEquals(Duration.ofMinutes(10), copy.getScheduleToCloseTimeout());
    assertEquals(Duration.ofSeconds(30), copy.getScheduleToStartTimeout());
    assertEquals(Duration.ofSeconds(15), copy.getHeartbeatTimeout());
    assertEquals(
        ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE, copy.getIdReusePolicy());
    assertEquals(
        ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL, copy.getIdConflictPolicy());
    assertEquals("summary", copy.getStaticSummary());
    assertEquals("details", copy.getStaticDetails());
    assertEquals(priority, copy.getPriority());
  }
}
