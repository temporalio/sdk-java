package io.temporal.worker.tuning;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;
import java.util.Objects;

/** Contains information about a slot that is being used to execute an activity task. */
@Experimental
public class ActivitySlotInfo extends SlotInfo {
  private final ActivityInfo activityInfo;
  private final String workerIdentity;
  private final String workerBuildId;

  public ActivitySlotInfo(ActivityInfo activityInfo, String workerIdentity, String workerBuildId) {
    this.activityInfo = activityInfo;
    this.workerIdentity = workerIdentity;
    this.workerBuildId = workerBuildId;
  }

  public ActivityInfo getActivityInfo() {
    return activityInfo;
  }

  public String getWorkerIdentity() {
    return workerIdentity;
  }

  public String getWorkerBuildId() {
    return workerBuildId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivitySlotInfo that = (ActivitySlotInfo) o;
    return Objects.equals(activityInfo, that.activityInfo)
        && Objects.equals(workerIdentity, that.workerIdentity)
        && Objects.equals(workerBuildId, that.workerBuildId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(activityInfo, workerIdentity, workerBuildId);
  }

  @Override
  public String toString() {
    return "ActivitySlotInfo{"
        + "activityInfo="
        + activityInfo
        + ", workerIdentity='"
        + workerIdentity
        + '\''
        + ", workerBuildId='"
        + workerBuildId
        + '\''
        + '}';
  }
}
