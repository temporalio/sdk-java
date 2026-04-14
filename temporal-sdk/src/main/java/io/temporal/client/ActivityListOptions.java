package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/** Options for {@link WorkflowClient#listActivities(String, ActivityListOptions)}. */
@Experimental
public final class ActivityListOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private @Nullable Integer limit;

    private Builder() {}

    private Builder(ActivityListOptions options) {
      this.limit = options.limit;
    }

    /** Maximum total number of results to return across all pages. */
    public Builder setLimit(int limit) {
      this.limit = limit;
      return this;
    }

    public ActivityListOptions build() {
      return new ActivityListOptions(this);
    }
  }

  private final @Nullable Integer limit;

  private ActivityListOptions(Builder builder) {
    this.limit = builder.limit;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public Integer getLimit() {
    return limit;
  }
}
