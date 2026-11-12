package io.temporal.internal.nexus;

import io.temporal.common.CancellationToken;
import io.temporal.nexus.NexusOperationInfo;
import java.util.concurrent.CancellationException;

class NexusInfoImpl implements NexusOperationInfo {
  private final String namespace;
  private final String taskQueue;
  private final String endpoint;
  private final String service;
  private final String operation;
  private final CancellationToken<CancellationException> cancellationToken;

  NexusInfoImpl(
      String namespace,
      String taskQueue,
      String endpoint,
      String service,
      String operation,
      CancellationToken<CancellationException> cancellationToken) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.endpoint = endpoint;
    this.service = service;
    this.operation = operation;
    this.cancellationToken = cancellationToken;
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

  @Override
  public String getService() {
    return service;
  }

  @Override
  public String getOperation() {
    return operation;
  }

  @Override
  public CancellationToken<CancellationException> getCancellationToken() {
    return cancellationToken;
  }
}
