package io.temporal.client;

import static org.junit.Assert.*;

import io.temporal.api.activity.v1.ActivityExecutionListInfo;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import org.junit.Test;

public class ActivityExecutionMetadataTest {

  private static ActivityExecutionListInfo buildListInfo(
      String activityId,
      String runId,
      String activityType,
      ActivityExecutionStatus status,
      String taskQueue) {
    return ActivityExecutionListInfo.newBuilder()
        .setActivityId(activityId)
        .setRunId(runId)
        .setActivityType(ActivityType.newBuilder().setName(activityType).build())
        .setStatus(status)
        .setTaskQueue(taskQueue)
        .build();
  }

  @Test
  public void testFromListInfo_basic() {
    ActivityExecutionListInfo proto =
        buildListInfo(
            "act-id",
            "run-id",
            "MyActivity",
            ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING,
            "tq");
    ActivityExecutionMetadata exec = ActivityExecutionMetadata.fromListInfo(proto);

    assertEquals("act-id", exec.getActivityId());
    assertEquals("run-id", exec.getActivityRunId());
    assertEquals("MyActivity", exec.getActivityType());
    assertEquals(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, exec.getStatus());
    assertEquals("tq", exec.getTaskQueue());
    assertNull(exec.getCloseTime());
    assertNull(exec.getExecutionDuration());
  }

  @Test
  public void testFromListInfo_nullRunIdWhenEmpty() {
    ActivityExecutionListInfo proto =
        buildListInfo(
            "act-id",
            "",
            "MyActivity",
            ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_COMPLETED,
            "tq");
    ActivityExecutionMetadata exec = ActivityExecutionMetadata.fromListInfo(proto);
    assertNull(exec.getActivityRunId());
  }

  @Test
  public void testFromListInfo_nonNullRunId() {
    ActivityExecutionListInfo proto =
        buildListInfo(
            "act-id",
            "some-run",
            "MyActivity",
            ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_COMPLETED,
            "tq");
    ActivityExecutionMetadata exec = ActivityExecutionMetadata.fromListInfo(proto);
    assertEquals("some-run", exec.getActivityRunId());
  }

  @Test
  public void testGetRawListInfo() {
    ActivityExecutionListInfo proto =
        buildListInfo(
            "act-id",
            "run-id",
            "MyActivity",
            ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING,
            "tq");
    ActivityExecutionMetadata exec = ActivityExecutionMetadata.fromListInfo(proto);
    assertSame(proto, exec.getRawListInfo());
  }
}
