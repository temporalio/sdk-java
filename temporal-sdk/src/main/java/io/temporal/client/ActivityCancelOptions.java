package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/** Options for {@link ActivityHandle#cancel(ActivityCancelOptions)}. */
@Experimental
public final class ActivityCancelOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private @Nullable String reason;

    private Builder() {}

    private Builder(ActivityCancelOptions options) {
      this.reason = options.reason;
    }

    /** Human-readable reason for the cancellation. */
    public Builder setReason(String reason) {
      this.reason = reason;
      return this;
    }

    public ActivityCancelOptions build() {
      return new ActivityCancelOptions(this);
    }
  }

  private final @Nullable String reason;

  private ActivityCancelOptions(Builder builder) {
    this.reason = builder.reason;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public String getReason() {
    return reason;
  }
}
