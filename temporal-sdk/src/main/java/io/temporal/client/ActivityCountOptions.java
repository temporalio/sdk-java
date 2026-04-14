package io.temporal.client;

import io.temporal.common.Experimental;

/** Options for {@link WorkflowClient#countActivities(String, ActivityCountOptions)}. */
@Experimental
public final class ActivityCountOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private Builder() {}

    private Builder(ActivityCountOptions options) {}

    public ActivityCountOptions build() {
      return new ActivityCountOptions(this);
    }
  }

  private ActivityCountOptions(Builder builder) {}

  public Builder toBuilder() {
    return new Builder(this);
  }
}
