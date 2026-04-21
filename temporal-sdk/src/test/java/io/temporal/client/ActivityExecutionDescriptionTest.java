package io.temporal.client;

import static org.junit.Assert.*;

import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Instant;
import org.junit.Test;

public class ActivityExecutionDescriptionTest {

  private static final DataConverter CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

  private DescribeActivityExecutionResponse buildResponse(String activityId, String runId) {
    ActivityExecutionInfo.Builder info =
        ActivityExecutionInfo.newBuilder()
            .setActivityId(activityId)
            .setActivityType(ActivityType.newBuilder().setName("MyActivity").build())
            .setStatus(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING)
            .setTaskQueue("my-queue")
            .setAttempt(2)
            .setScheduleTime(ProtobufTimeUtils.toProtoTimestamp(Instant.ofEpochMilli(1000)));

    return DescribeActivityExecutionResponse.newBuilder()
        .setRunId(runId)
        .setInfo(info.build())
        .build();
  }

  @Test
  public void testBasicFields() {
    DescribeActivityExecutionResponse response = buildResponse("act-id", "run-123");
    ActivityExecutionDescription desc = new ActivityExecutionDescription(response, CONVERTER);

    assertEquals("act-id", desc.getActivityId());
    assertEquals("run-123", desc.getActivityRunId());
    assertEquals("MyActivity", desc.getActivityType());
    assertEquals(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, desc.getStatus());
    assertEquals("my-queue", desc.getTaskQueue());
    assertEquals(2, desc.getAttempt());
  }

  @Test
  public void testNullRunIdWhenEmpty() {
    DescribeActivityExecutionResponse response = buildResponse("act-id", "");
    ActivityExecutionDescription desc = new ActivityExecutionDescription(response, CONVERTER);
    assertNull(desc.getActivityRunId());
  }

  @Test
  public void testNullableFieldsAbsentByDefault() {
    DescribeActivityExecutionResponse response = buildResponse("act-id", "");
    ActivityExecutionDescription desc = new ActivityExecutionDescription(response, CONVERTER);

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
    assertNull(desc.getRetryPolicy());
    assertNull(desc.getStaticSummary());
    assertNull(desc.getStaticDetails());
  }

  @Test
  public void testScheduledTime() {
    DescribeActivityExecutionResponse response = buildResponse("act-id", "");
    ActivityExecutionDescription desc = new ActivityExecutionDescription(response, CONVERTER);
    assertNotNull(desc.getScheduledTime());
    assertEquals(Instant.ofEpochMilli(1000), desc.getScheduledTime());
  }

  @Test
  public void testIsInstanceOfActivityExecution() {
    DescribeActivityExecutionResponse response = buildResponse("id", "run");
    ActivityExecutionDescription desc = new ActivityExecutionDescription(response, CONVERTER);
    assertTrue(desc instanceof ActivityExecutionMetadata);
  }

  @Test
  public void testGetRawDescription() {
    DescribeActivityExecutionResponse response = buildResponse("act-id", "run");
    ActivityExecutionDescription desc = new ActivityExecutionDescription(response, CONVERTER);
    assertSame(response, desc.getRawDescription());
  }
}
