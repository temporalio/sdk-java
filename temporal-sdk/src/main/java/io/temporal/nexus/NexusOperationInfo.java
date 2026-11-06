package io.temporal.nexus;

import io.temporal.common.CancellationToken;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import javax.annotation.Nullable;

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

  // :>) add comment
  String getService();

  // :>) add comment
  String getOperation();

  // :>) add comment
  @Nullable
  Instant getDeadline();

  CancellationToken<CancellationException> getCancellationToken();
}
