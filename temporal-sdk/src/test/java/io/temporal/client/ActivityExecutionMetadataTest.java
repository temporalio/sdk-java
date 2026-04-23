package io.temporal.client;

import static org.junit.Assert.*;

import io.temporal.api.activity.v1.ActivityExecutionListInfo;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import org.junit.Test;

public class ActivityExecutionMetadataTest {

  private static ActivityExecutionListInfo buildListInfo(String activityId, String runId) {
    return ActivityExecutionListInfo.newBuilder()
        .setActivityId(activityId)
        .setRunId(runId)
        .setActivityType(ActivityType.newBuilder().setName("MyActivity").build())
        .setStatus(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_COMPLETED)
        .setTaskQueue("tq")
        .build();
  }

  @Test
  public void testNullRunIdWhenEmpty() {
    ActivityExecutionMetadata exec =
        ActivityExecutionMetadata.fromListInfo(buildListInfo("id", ""));
    assertNull(exec.getActivityRunId());
  }
}
