package io.temporal.nexus;

import io.temporal.common.CancellationToken;
import java.util.concurrent.CancellationException;

/**
 * Temporal information about the Nexus Operation. Use {@link Nexus#getInfo()} from a Nexus
 * Operation implementation to access.
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

  /**
   * @return Service name for the current Nexus request.
   */
  String getService();

  /**
   * @return Operation name for the current Nexus request.
   */
  String getOperation();

  /**
   * Returns a read-only token for observing cancellation of the current handler invocation. This is
   * separate from a request to cancel the Nexus Operation itself.
   *
   * <p>When cancellation is requested, {@link CancellationToken#throwIfCancellationRequested()}
   * throws a {@link CancellationException} whose message contains the invocation cancellation
   * reason.
   */
  CancellationToken<CancellationException> getCancellationToken();
}
