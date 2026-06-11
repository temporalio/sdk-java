package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import java.time.Duration;

/**
 * Fixed handler names and error types of the workflow streams wire protocol. These are part of the
 * cross-language contract and match the Go, Python, and TypeScript packages exactly. The Java SDK
 * normally reserves the {@code __temporal_} prefix, but explicitly permits the {@code
 * __temporal_workflow_stream_} sub-namespace for this package.
 */
@Experimental
public final class WorkflowStreamConstants {
  /** Signal external publishers send to append a batch of items to the stream. */
  public static final String PUBLISH_SIGNAL_NAME = "__temporal_workflow_stream_publish";

  /** Update subscribers send to long-poll for new items. */
  public static final String POLL_UPDATE_NAME = "__temporal_workflow_stream_poll";

  /** Query that returns the current global offset. */
  public static final String OFFSET_QUERY_NAME = "__temporal_workflow_stream_offset";

  /**
   * ApplicationFailure type returned by the poll update when the requested offset has already been
   * truncated.
   */
  public static final String ERROR_TYPE_TRUNCATED_OFFSET = "TruncatedOffset";

  /**
   * ApplicationFailure type thrown by {@link WorkflowStream#truncate} when the requested offset is
   * past the end of the log.
   */
  public static final String ERROR_TYPE_TRUNCATE_OUT_OF_RANGE = "TruncateOutOfRange";

  /**
   * ApplicationFailure type the poll update's validator returns while the stream is detaching for
   * continue-as-new. It tells a subscriber the rollover is in progress so it retries (rather than
   * surfacing an error) until the poll lands on the successor run.
   */
  public static final String ERROR_TYPE_STREAM_DRAINING = "StreamDraining";

  /**
   * Caps the estimated wire size of a single poll response. Responses that would exceed this are
   * truncated and signal {@code more_ready} so the subscriber pages through the remainder.
   */
  static final int MAX_POLL_RESPONSE_BYTES = 1_000_000;

  // Default option values, matching the Go, Python, and TypeScript packages.
  static final Duration DEFAULT_BATCH_INTERVAL = Duration.ofSeconds(2);
  static final Duration DEFAULT_POLL_COOLDOWN = Duration.ofMillis(100);
  static final Duration DEFAULT_PUBLISHER_TTL = Duration.ofMinutes(15);
  static final Duration DEFAULT_MAX_RETRY_DURATION = Duration.ofMinutes(10);

  private WorkflowStreamConstants() {}
}
