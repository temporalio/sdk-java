package io.temporal.client;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Options for {@link UntypedActivityHandle#unpause(UnpauseActivityOptions)}.
 *
 * <p>All fields are optional. An instance with no fields set unpauses the activity with default
 * behavior.
 */
@Experimental
public final class UnpauseActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(UnpauseActivityOptions options) {
    return new Builder(options);
  }

  public static UnpauseActivityOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final UnpauseActivityOptions DEFAULT_INSTANCE =
      UnpauseActivityOptions.newBuilder().build();

  public static final class Builder {
    private @Nullable String reason;
    private boolean resetAttempts;
    private boolean resetHeartbeat;
    private @Nullable Duration jitter;

    private Builder() {}

    private Builder(UnpauseActivityOptions options) {
      if (options == null) {
        return;
      }
      this.reason = options.reason;
      this.resetAttempts = options.resetAttempts;
      this.resetHeartbeat = options.resetHeartbeat;
      this.jitter = options.jitter;
    }

    /** Human-readable reason for unpausing. */
    public Builder setReason(@Nullable String reason) {
      this.reason = reason;
      return this;
    }

    /** If set, also resets the activity's attempt counter back to 1. */
    public Builder setResetAttempts(boolean resetAttempts) {
      this.resetAttempts = resetAttempts;
      return this;
    }

    /** If set, also clears the activity's recorded heartbeat details. */
    public Builder setResetHeartbeat(boolean resetHeartbeat) {
      this.resetHeartbeat = resetHeartbeat;
      return this;
    }

    /** If set, the activity will resume at a random time within the given jitter window. */
    public Builder setJitter(@Nullable Duration jitter) {
      this.jitter = jitter;
      return this;
    }

    public UnpauseActivityOptions build() {
      return new UnpauseActivityOptions(this);
    }
  }

  private final @Nullable String reason;
  private final boolean resetAttempts;
  private final boolean resetHeartbeat;
  private final @Nullable Duration jitter;

  private UnpauseActivityOptions(Builder builder) {
    this.reason = builder.reason;
    this.resetAttempts = builder.resetAttempts;
    this.resetHeartbeat = builder.resetHeartbeat;
    this.jitter = builder.jitter;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public String getReason() {
    return reason;
  }

  public boolean isResetAttempts() {
    return resetAttempts;
  }

  public boolean isResetHeartbeat() {
    return resetHeartbeat;
  }

  @Nullable
  public Duration getJitter() {
    return jitter;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    UnpauseActivityOptions that = (UnpauseActivityOptions) o;
    return resetAttempts == that.resetAttempts
        && resetHeartbeat == that.resetHeartbeat
        && Objects.equals(reason, that.reason)
        && Objects.equals(jitter, that.jitter);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reason, resetAttempts, resetHeartbeat, jitter);
  }

  @Override
  public String toString() {
    return "UnpauseActivityOptions{"
        + "reason='"
        + reason
        + "', resetAttempts="
        + resetAttempts
        + ", resetHeartbeat="
        + resetHeartbeat
        + ", jitter="
        + jitter
        + '}';
  }
}
