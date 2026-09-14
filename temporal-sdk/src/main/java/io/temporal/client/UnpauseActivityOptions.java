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
    private @Nullable Duration jitter;

    private Builder() {}

    private Builder(UnpauseActivityOptions options) {
      if (options == null) {
        return;
      }
      this.reason = options.reason;
      this.jitter = options.jitter;
    }

    /** Human-readable reason for unpausing. */
    public Builder setReason(@Nullable String reason) {
      this.reason = reason;
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
  private final @Nullable Duration jitter;

  private UnpauseActivityOptions(Builder builder) {
    this.reason = builder.reason;
    this.jitter = builder.jitter;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public String getReason() {
    return reason;
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
    return Objects.equals(reason, that.reason) && Objects.equals(jitter, that.jitter);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reason, jitter);
  }

  @Override
  public String toString() {
    return "UnpauseActivityOptions{" + "reason='" + reason + "', jitter=" + jitter + '}';
  }
}
