package io.temporal.internal.nexus;

import io.temporal.nexus.NexusInfo;

class NexusInfoImpl implements NexusInfo {
  private final String namespace;
  private final String taskQueue;

  NexusInfoImpl(String namespace, String taskQueue) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
  }

  @Override
  public String getNamespace() {
    return namespace;
  }

  @Override
  public String getTaskQueue() {
    return taskQueue;
  }
}
