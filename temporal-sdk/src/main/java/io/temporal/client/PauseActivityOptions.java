package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Options for {@link UntypedActivityHandle#pause(PauseActivityOptions)}.
 *
 * <p>All fields are optional. An instance with no fields set pauses the activity with default
 * behavior.
 */
@Experimental
public final class PauseActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(PauseActivityOptions options) {
    return new Builder(options);
  }

  public static PauseActivityOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final PauseActivityOptions DEFAULT_INSTANCE =
      PauseActivityOptions.newBuilder().build();

  public static final class Builder {
    private @Nullable String reason;

    private Builder() {}

    private Builder(PauseActivityOptions options) {
      if (options == null) {
        return;
      }
      this.reason = options.reason;
    }

    /** Human-readable reason for pausing, recorded on the server. */
    public Builder setReason(@Nullable String reason) {
      this.reason = reason;
      return this;
    }

    public PauseActivityOptions build() {
      return new PauseActivityOptions(this);
    }
  }

  private final @Nullable String reason;

  private PauseActivityOptions(Builder builder) {
    this.reason = builder.reason;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public String getReason() {
    return reason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PauseActivityOptions that = (PauseActivityOptions) o;
    return Objects.equals(reason, that.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reason);
  }

  @Override
  public String toString() {
    return "PauseActivityOptions{" + "reason='" + reason + "'" + '}';
  }
}
