package io.temporal.client;

import com.google.common.base.Strings;

public class TemporalNexusServiceClientOptions {
  public static Builder newBuilder() {
    return new Builder();
  }

  private final String endpoint;
  private final String taskQueue;

  TemporalNexusServiceClientOptions(String endpoint, String taskQueue) {
    this.endpoint = endpoint;
    this.taskQueue = taskQueue;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public static final class Builder {
    private String endpoint;
    private String taskQueue;

    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    public Builder setEndpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public TemporalNexusServiceClientOptions build() {
      if (Strings.isNullOrEmpty(endpoint) && Strings.isNullOrEmpty(taskQueue)) {
        throw new IllegalArgumentException("Must provide either a task queue or an endpoint");
      } else if (!Strings.isNullOrEmpty(endpoint) && !Strings.isNullOrEmpty(taskQueue)) {
        throw new IllegalArgumentException("Must provide only a task queue or an endpoint");
      }

      return new TemporalNexusServiceClientOptions(endpoint, taskQueue);
    }
  }
}
