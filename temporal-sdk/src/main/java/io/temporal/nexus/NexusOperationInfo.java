package io.temporal.nexus;

/**
 * Temporal information about the Nexus Operation. Use {@link NexusOperationContext#getInfo()} from
 * a Nexus Operation implementation to access.
 */
public interface NexusOperationInfo {
  /**
   * @return Namespace of the worker that is executing the Nexus Operation
   */
  String getNamespace();

  /**
   * @return Nexus Task Queue of the worker that is executing the Nexus Operation
   */
  String getTaskQueue();

  /**
   * @return Endpoint that the Nexus request was addressed to before being forwarded to this worker.
   *     Supported from server v1.30.0.
   */
  String getEndpoint();
}
