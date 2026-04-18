package io.temporal.internal.nexus;

import io.temporal.nexus.NexusOperationInfo;

class NexusInfoImpl implements NexusOperationInfo {
  private final String namespace;
  private final String taskQueue;
  private final String endpoint;

  NexusInfoImpl(String namespace, String taskQueue, String endpoint) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.endpoint = endpoint;
  }

  @Override
  public String getNamespace() {
    return namespace;
  }

  @Override
  public String getTaskQueue() {
    return taskQueue;
  }

  @Override
  public String getEndpoint() {
    return endpoint;
  }
}
