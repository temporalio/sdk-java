package io.temporal.nexus;

import io.temporal.common.CancellationToken;
import java.util.concurrent.CancellationException;
import javax.annotation.Nullable;

/**
 * Temporal information about the Nexus Operation. Use {@link NexusOperationContext#getInfo()
 * Nexus.getOperationContext().getInfo()} from a Nexus Operation implementation to access.
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
   * Returns the ID of the current Nexus start request, or {@code null} when no ID is available (for
   * example, in a cancel handler).
   *
   * <p>Retries of the same start request carry the same ID. Treat the ID as an opaque string.
   */
  @Nullable
  default String getRequestId() {
    return null;
  }

  /**
   * Returns a read-only token for observing cancellation of the current handler invocation. This is
   * separate from a request to cancel the Nexus Operation itself.
   *
   * <p>When cancellation is requested, {@link CancellationToken#throwIfCancellationRequested()}
   * throws a {@link CancellationException} whose message contains the invocation cancellation
   * reason.
   *
   * <p>Cancellation callbacks may run on the Nexus timeout thread and must return promptly.
   *
   * <p>The SDK's default JSON converter omits the live token when serializing this info.
   */
  CancellationToken<CancellationException> getCancellationToken();
}
