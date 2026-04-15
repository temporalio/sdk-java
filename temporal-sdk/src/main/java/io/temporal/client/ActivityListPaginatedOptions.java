package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Options for {@link ActivityClient#listExecutionsPaginated(String, byte[],
 * ActivityListPaginatedOptions)}.
 */
@Experimental
public final class ActivityListPaginatedOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private @Nullable Integer pageSize;

    private Builder() {}

    private Builder(ActivityListPaginatedOptions options) {
      this.pageSize = options.pageSize;
    }

    /** Number of results per page. Server default is used if not set. */
    public Builder setPageSize(int pageSize) {
      this.pageSize = pageSize;
      return this;
    }

    public ActivityListPaginatedOptions build() {
      return new ActivityListPaginatedOptions(this);
    }
  }

  private final @Nullable Integer pageSize;

  private ActivityListPaginatedOptions(Builder builder) {
    this.pageSize = builder.pageSize;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Nullable
  public Integer getPageSize() {
    return pageSize;
  }
}
