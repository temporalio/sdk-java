package io.temporal.worker;

import io.temporal.serviceclient.MetricsTag;

public class PollerTypeMetricsTag {
  public enum PollerType implements MetricsTag.TagValue {
    WORKFLOW_TASK("workflow_task"),
    WORKFLOW_STICKY_TASK("workflow_sticky_task"),
    ACTIVITY_TASK("activity_task"),
    NEXUS_TASK("nexus_task"),
    ;

    PollerType(String value) {
      this.value = value;
    }

    private final String value;

    @Override
    public String getTag() {
      return MetricsTag.POLLER_TYPE;
    }

    public String getValue() {
      return value;
    }
  }
}
