package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.Objects;

/**
 * Options for {@link UntypedActivityHandle#describe(DescribeActivityOptions)}.
 *
 * <p>Each flag opts in to a field on the description that carries a payload. Payloads can be
 * arbitrarily large, so none are returned unless explicitly requested. An instance with no fields
 * set describes the activity without any of them.
 */
@Experimental
public final class DescribeActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(DescribeActivityOptions options) {
    return new Builder(options);
  }

  public static DescribeActivityOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final DescribeActivityOptions DEFAULT_INSTANCE =
      DescribeActivityOptions.newBuilder().build();

  public static final class Builder {
    private boolean includeInput;
    private boolean includeOutcome;
    private boolean includeHeartbeatDetails;
    private boolean includeLastFailure;

    private Builder() {}

    private Builder(DescribeActivityOptions options) {
      if (options == null) {
        return;
      }
      this.includeInput = options.includeInput;
      this.includeOutcome = options.includeOutcome;
      this.includeHeartbeatDetails = options.includeHeartbeatDetails;
      this.includeLastFailure = options.includeLastFailure;
    }

    /** If set and the activity received input, the description includes the input. */
    public Builder setIncludeInput(boolean includeInput) {
      this.includeInput = includeInput;
      return this;
    }

    /** If set and the activity is closed, the description includes the outcome. */
    public Builder setIncludeOutcome(boolean includeOutcome) {
      this.includeOutcome = includeOutcome;
      return this;
    }

    /**
     * If set and the activity recorded heartbeat details, the description includes the details of
     * the last heartbeat.
     */
    public Builder setIncludeHeartbeatDetails(boolean includeHeartbeatDetails) {
      this.includeHeartbeatDetails = includeHeartbeatDetails;
      return this;
    }

    /**
     * If set and the activity has a failed attempt, the description includes the failure of the
     * last failed attempt.
     */
    public Builder setIncludeLastFailure(boolean includeLastFailure) {
      this.includeLastFailure = includeLastFailure;
      return this;
    }

    public DescribeActivityOptions build() {
      return new DescribeActivityOptions(this);
    }
  }

  private final boolean includeInput;
  private final boolean includeOutcome;
  private final boolean includeHeartbeatDetails;
  private final boolean includeLastFailure;

  private DescribeActivityOptions(Builder builder) {
    this.includeInput = builder.includeInput;
    this.includeOutcome = builder.includeOutcome;
    this.includeHeartbeatDetails = builder.includeHeartbeatDetails;
    this.includeLastFailure = builder.includeLastFailure;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public boolean isIncludeInput() {
    return includeInput;
  }

  public boolean isIncludeOutcome() {
    return includeOutcome;
  }

  public boolean isIncludeHeartbeatDetails() {
    return includeHeartbeatDetails;
  }

  public boolean isIncludeLastFailure() {
    return includeLastFailure;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DescribeActivityOptions that = (DescribeActivityOptions) o;
    return includeInput == that.includeInput
        && includeOutcome == that.includeOutcome
        && includeHeartbeatDetails == that.includeHeartbeatDetails
        && includeLastFailure == that.includeLastFailure;
  }

  @Override
  public int hashCode() {
    return Objects.hash(includeInput, includeOutcome, includeHeartbeatDetails, includeLastFailure);
  }

  @Override
  public String toString() {
    return "DescribeActivityOptions{"
        + "includeInput="
        + includeInput
        + ", includeOutcome="
        + includeOutcome
        + ", includeHeartbeatDetails="
        + includeHeartbeatDetails
        + ", includeLastFailure="
        + includeLastFailure
        + '}';
  }
}
