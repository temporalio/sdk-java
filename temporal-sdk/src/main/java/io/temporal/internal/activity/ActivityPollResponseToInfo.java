package io.temporal.internal.activity;

import io.temporal.activity.ActivityInfo;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;

public class ActivityPollResponseToInfo {
  public static ActivityInfo toActivityInfoImpl(
      PollActivityTaskQueueResponseOrBuilder response,
      String namespace,
      String activityTaskQueue,
      boolean local) {
    return new ActivityInfoImpl(response, namespace, activityTaskQueue, local, null);
  }
}
