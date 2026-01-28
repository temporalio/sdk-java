package io.temporal.worker;

import io.temporal.serviceclient.MetricsTag;

public class WorkerMetricsTag {
  public enum WorkerType implements MetricsTag.TagValue {
    WORKFLOW_WORKER("WorkflowWorker"),
    ACTIVITY_WORKER("ActivityWorker"),
    LOCAL_ACTIVITY_WORKER("LocalActivityWorker"),
    NEXUS_WORKER("NexusWorker");

    WorkerType(String value) {
      this.value = value;
    }

    private final String value;

    @Override
    public String getTag() {
      return MetricsTag.WORKER_TYPE;
    }

    public String getValue() {
      return value;
    }
  }
}
