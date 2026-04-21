package io.temporal.client;

import static org.junit.Assert.*;

import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Instant;
import org.junit.Test;

public class ActivityExecutionDescriptionTest {

  private static final DataConverter CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

  private ActivityExecutionInfo buildInfo(String activityId, String runId) {
    return ActivityExecutionInfo.newBuilder()
        .setActivityId(activityId)
        .setRunId(runId)
        .setActivityType(ActivityType.newBuilder().setName("MyActivity").build())
        .setStatus(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING)
        .setTaskQueue("my-queue")
        .setAttempt(2)
        .setScheduleTime(ProtobufTimeUtils.toProtoTimestamp(Instant.ofEpochMilli(1000)))
        .build();
  }

  @Test
  public void testBasicFields() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", "run-123"), CONVERTER);

    assertEquals("act-id", desc.getActivityId());
    assertEquals("run-123", desc.getActivityRunId());
    assertEquals("MyActivity", desc.getActivityType());
    assertEquals(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, desc.getStatus());
    assertEquals("my-queue", desc.getTaskQueue());
    assertEquals(2, desc.getAttempt());
  }

  @Test
  public void testNullRunIdWhenEmpty() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER);
    assertNull(desc.getActivityRunId());
  }

  @Test
  public void testNullableFieldsAbsentByDefault() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER);

    // These are all absent in the minimal response
    assertNull(desc.getCloseTime());
    assertNull(desc.getExecutionDuration());
    assertNull(desc.getCanceledReason());
    assertNull(desc.getCurrentRetryInterval());
    assertNull(desc.getExpirationTime());
    assertNull(desc.getHeartbeatTimeout());
    assertNull(desc.getLastAttemptCompleteTime());
    assertNull(desc.getLastHeartbeatTime());
    assertNull(desc.getLastStartedTime());
    assertNull(desc.getLastWorkerIdentity());
    assertNull(desc.getNextAttemptScheduleTime());
    assertNull(desc.getRetryOptions());
    assertNull(desc.getStaticSummary());
    assertNull(desc.getStaticDetails());
  }

  @Test
  public void testScheduledTime() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER);
    assertNotNull(desc.getScheduledTime());
    assertEquals(Instant.ofEpochMilli(1000), desc.getScheduledTime());
  }

  @Test
  public void testIsInstanceOfActivityExecution() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER);
    assertTrue(desc instanceof ActivityExecutionMetadata);
  }
}
