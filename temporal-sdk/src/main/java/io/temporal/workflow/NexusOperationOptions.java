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
      this.summary = options.getSummary();
    }

    public NexusOperationOptions build() {
      return new NexusOperationOptions(scheduleToCloseTimeout, summary);
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
      this.summary = (override.summary == null) ? this.summary : override.summary;
      return this;
    }
  }

  private NexusOperationOptions(Duration scheduleToCloseTimeout, String summary) {
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.summary = summary;
  }

  public NexusOperationOptions.Builder toBuilder() {
    return new NexusOperationOptions.Builder(this);
  }

  private final Duration scheduleToCloseTimeout;
  private final String summary;

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
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
        && Objects.equals(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheduleToCloseTimeout, summary);
  }

  @Override
  public String toString() {
    return "NexusOperationOptions{"
        + "scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", summary='"
        + summary
        + '\''
        + '}';
  }
}
