package io.temporal.client;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Options for {@link UntypedActivityHandle#reset(ResetActivityOptions)}.
 *
 * <p>All fields are optional. An instance with no fields set resets the activity with default
 * behavior.
 */
@Experimental
public final class ResetActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ResetActivityOptions options) {
    return new Builder(options);
  }

  public static ResetActivityOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ResetActivityOptions DEFAULT_INSTANCE =
      ResetActivityOptions.newBuilder().build();

  public static final class Builder {
    private boolean resetHeartbeat;
    private boolean keepPaused;
    private @Nullable Duration jitter;
    private boolean restoreOriginalOptions;

    private Builder() {}

    private Builder(ResetActivityOptions options) {
      if (options == null) {
        return;
      }
      this.resetHeartbeat = options.resetHeartbeat;
      this.keepPaused = options.keepPaused;
      this.jitter = options.jitter;
      this.restoreOriginalOptions = options.restoreOriginalOptions;
    }

    /** If set, the reset activity will clear its recorded heartbeat details. */
    public Builder setResetHeartbeat(boolean resetHeartbeat) {
      this.resetHeartbeat = resetHeartbeat;
      return this;
    }

    /** If set and the activity is paused, it will remain paused after the reset. */
    public Builder setKeepPaused(boolean keepPaused) {
      this.keepPaused = keepPaused;
      return this;
    }

    /**
     * If set and the activity is in backoff, the activity will start at a random time within the
     * given jitter window (unless it is paused and {@link #setKeepPaused(boolean)} is set).
     */
    public Builder setJitter(@Nullable Duration jitter) {
      this.jitter = jitter;
      return this;
    }

    /**
     * If set, the activity options are restored to the originals the activity was created with (the
     * options recorded in the first schedule event).
     *
     * <p>This flag may be combined with other reset settings.
     */
    public Builder setRestoreOriginalOptions(boolean restoreOriginalOptions) {
      this.restoreOriginalOptions = restoreOriginalOptions;
      return this;
    }

    public ResetActivityOptions build() {
      return new ResetActivityOptions(this);
    }
  }

  private final boolean resetHeartbeat;
  private final boolean keepPaused;
  private final @Nullable Duration jitter;
  private final boolean restoreOriginalOptions;

  private ResetActivityOptions(Builder builder) {
    this.resetHeartbeat = builder.resetHeartbeat;
    this.keepPaused = builder.keepPaused;
    this.jitter = builder.jitter;
    this.restoreOriginalOptions = builder.restoreOriginalOptions;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public boolean isResetHeartbeat() {
    return resetHeartbeat;
  }

  public boolean isKeepPaused() {
    return keepPaused;
  }

  @Nullable
  public Duration getJitter() {
    return jitter;
  }

  public boolean isRestoreOriginalOptions() {
    return restoreOriginalOptions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ResetActivityOptions that = (ResetActivityOptions) o;
    return resetHeartbeat == that.resetHeartbeat
        && keepPaused == that.keepPaused
        && restoreOriginalOptions == that.restoreOriginalOptions
        && Objects.equals(jitter, that.jitter);
  }

  @Override
  public int hashCode() {
    return Objects.hash(resetHeartbeat, keepPaused, jitter, restoreOriginalOptions);
  }

  @Override
  public String toString() {
    return "ResetActivityOptions{"
        + "resetHeartbeat="
        + resetHeartbeat
        + ", keepPaused="
        + keepPaused
        + ", jitter="
        + jitter
        + ", restoreOriginalOptions="
        + restoreOriginalOptions
        + '}';
  }
}
