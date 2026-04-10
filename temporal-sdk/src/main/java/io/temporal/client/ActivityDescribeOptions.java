package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/** Options for {@link ActivityHandle#describe(ActivityDescribeOptions)}. */
@Experimental
public final class ActivityDescribeOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private @Nullable byte[] longPollToken;

    private Builder() {}

    /**
     * Long-poll token returned by a previous describe call. When set, the server will block until
     * there is a state change before returning.
     */
    public Builder setLongPollToken(byte[] longPollToken) {
      this.longPollToken = longPollToken;
      return this;
    }

    public ActivityDescribeOptions build() {
      return new ActivityDescribeOptions(this);
    }
  }

  private final @Nullable byte[] longPollToken;

  private ActivityDescribeOptions(Builder builder) {
    this.longPollToken = builder.longPollToken;
  }

  public Builder toBuilder() {
    return new Builder();
  }

  @Nullable
  public byte[] getLongPollToken() {
    return longPollToken;
  }
}
