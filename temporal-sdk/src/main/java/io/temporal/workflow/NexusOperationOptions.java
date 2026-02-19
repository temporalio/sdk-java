package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;

/**
 * NexusOperationOptions is used to specify the options for starting a Nexus operation from a
 * Workflow.
 *
 * <p>Use {@link NexusOperationOptions#newBuilder()} to construct an instance.
 */
public final class NexusOperationOptions {
  public static NexusOperationOptions.Builder newBuilder() {
    return new NexusOperationOptions.Builder();
  }

  public static NexusOperationOptions.Builder newBuilder(NexusOperationOptions options) {
    return new NexusOperationOptions.Builder(options);
  }

  public static NexusOperationOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final NexusOperationOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = NexusOperationOptions.newBuilder().build();
  }

  public static final class Builder {
    private Duration scheduleToCloseTimeout;
    private Duration scheduleToStartTimeout;
    private Duration startToCloseTimeout;
    private NexusOperationCancellationType cancellationType;
    private String summary;

    /**
     * Sets the schedule to close timeout for the Nexus operation.
     *
     * @param scheduleToCloseTimeout the schedule to close timeout for the Nexus operation
     * @return this
     */
    public NexusOperationOptions.Builder setScheduleToCloseTimeout(
        Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /**
     * Sets the schedule to start timeout for the Nexus operation.
     *
     * <p>Maximum time to wait for the operation to be started (or completed if synchronous) by the
     * handler. If the operation is not started within this timeout, it will fail with
     * TIMEOUT_TYPE_SCHEDULE_TO_START.
     *
     * <p>Requires Temporal Server 1.31.0 or later.
     *
     * @param scheduleToStartTimeout the schedule to start timeout for the Nexus operation
     * @return this
     */
    @Experimental
    public NexusOperationOptions.Builder setScheduleToStartTimeout(
        Duration scheduleToStartTimeout) {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      return this;
    }

    /**
     * Sets the start to close timeout for the Nexus operation.
     *
     * <p>Maximum time to wait for an asynchronous operation to complete after it has been started.
     * If the operation does not complete within this timeout after starting, it will fail with
     * TIMEOUT_TYPE_START_TO_CLOSE.
     *
     * <p>Only applies to asynchronous operations. Synchronous operations ignore this timeout.
     *
     * <p>Requires Temporal Server 1.31.0 or later.
     *
     * @param startToCloseTimeout the start to close timeout for the Nexus operation
     * @return this
     */
    @Experimental
    public NexusOperationOptions.Builder setStartToCloseTimeout(Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /**
     * Sets the cancellation type for the Nexus operation. Defaults to WAIT_COMPLETED.
     *
     * @param cancellationType the cancellation type for the Nexus operation
     * @return this
     */
    @Experimental
    public NexusOperationOptions.Builder setCancellationType(
        NexusOperationCancellationType cancellationType) {
      this.cancellationType = cancellationType;
      return this;
    }

    /**
     * Single-line fixed summary for this Nexus operation that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public NexusOperationOptions.Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    private Builder() {}

    private Builder(NexusOperationOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
      this.scheduleToStartTimeout = options.getScheduleToStartTimeout();
      this.startToCloseTimeout = options.getStartToCloseTimeout();
      this.cancellationType = options.getCancellationType();
      this.summary = options.getSummary();
    }

    public NexusOperationOptions build() {
      return new NexusOperationOptions(
          scheduleToCloseTimeout,
          scheduleToStartTimeout,
          startToCloseTimeout,
          cancellationType,
          summary);
    }

    public NexusOperationOptions.Builder mergeNexusOperationOptions(
        NexusOperationOptions override) {
      if (override == null) {
        return this;
      }
      this.scheduleToCloseTimeout =
          (override.scheduleToCloseTimeout == null)
              ? this.scheduleToCloseTimeout
              : override.scheduleToCloseTimeout;
      this.scheduleToStartTimeout =
          (override.scheduleToStartTimeout == null)
              ? this.scheduleToStartTimeout
              : override.scheduleToStartTimeout;
      this.startToCloseTimeout =
          (override.startToCloseTimeout == null)
              ? this.startToCloseTimeout
              : override.startToCloseTimeout;
      this.cancellationType =
          (override.cancellationType == null) ? this.cancellationType : override.cancellationType;
      this.summary = (override.summary == null) ? this.summary : override.summary;
      return this;
    }
  }

  private NexusOperationOptions(
      Duration scheduleToCloseTimeout,
      Duration scheduleToStartTimeout,
      Duration startToCloseTimeout,
      NexusOperationCancellationType cancellationType,
      String summary) {
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.scheduleToStartTimeout = scheduleToStartTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.cancellationType = cancellationType;
    this.summary = summary;
  }

  public NexusOperationOptions.Builder toBuilder() {
    return new NexusOperationOptions.Builder(this);
  }

  private final Duration scheduleToCloseTimeout;
  private final Duration scheduleToStartTimeout;
  private final Duration startToCloseTimeout;
  private final NexusOperationCancellationType cancellationType;
  private final String summary;

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  @Experimental
  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  @Experimental
  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  public NexusOperationCancellationType getCancellationType() {
    return cancellationType;
  }

  @Experimental
  public String getSummary() {
    return summary;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NexusOperationOptions that = (NexusOperationOptions) o;
    return Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equals(scheduleToStartTimeout, that.scheduleToStartTimeout)
        && Objects.equals(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equals(cancellationType, that.cancellationType)
        && Objects.equals(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        scheduleToCloseTimeout,
        scheduleToStartTimeout,
        startToCloseTimeout,
        cancellationType,
        summary);
  }

  @Override
  public String toString() {
    return "NexusOperationOptions{"
        + "scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", scheduleToStartTimeout="
        + scheduleToStartTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", cancellationType="
        + cancellationType
        + ", summary='"
        + summary
        + '\''
        + '}';
  }
}
