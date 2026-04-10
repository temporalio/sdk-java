package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/** Options for {@link ActivityHandle#terminate(String, ActivityTerminateOptions)}. */
@Experimental
public final class ActivityTerminateOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private @Nullable String reason;

    private Builder() {}

    /** Human-readable reason for the termination. */
    public Builder setReason(String reason) {
      this.reason = reason;
      return this;
    }

    public ActivityTerminateOptions build() {
      return new ActivityTerminateOptions(this);
    }
  }

  private final @Nullable String reason;

  private ActivityTerminateOptions(Builder builder) {
    this.reason = builder.reason;
  }

  public Builder toBuilder() {
    return new Builder();
  }

  @Nullable
  public String getReason() {
    return reason;
  }
}
