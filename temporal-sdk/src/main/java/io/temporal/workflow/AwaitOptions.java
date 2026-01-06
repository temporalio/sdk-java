package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Objects;

/** Options for await operations that involve timers. */
public final class AwaitOptions {

  public static AwaitOptions.Builder newBuilder() {
    return new AwaitOptions.Builder();
  }

  public static AwaitOptions.Builder newBuilder(AwaitOptions options) {
    return new AwaitOptions.Builder(options);
  }

  public static AwaitOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final AwaitOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = AwaitOptions.newBuilder().build();
  }

  public static final class Builder {
    private String timerSummary;

    private Builder() {}

    private Builder(AwaitOptions options) {
      if (options == null) {
        return;
      }
      this.timerSummary = options.timerSummary;
    }

    /**
     * Single-line fixed summary for the timer used by timed await operations. This will appear in
     * UI/CLI. Can be in single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setTimerSummary(String timerSummary) {
      this.timerSummary = timerSummary;
      return this;
    }

    public AwaitOptions build() {
      return new AwaitOptions(timerSummary);
    }
  }

  private final String timerSummary;

  private AwaitOptions(String timerSummary) {
    this.timerSummary = timerSummary;
  }

  public String getTimerSummary() {
    return timerSummary;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "AwaitOptions{" + "timerSummary='" + timerSummary + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AwaitOptions that = (AwaitOptions) o;
    return Objects.equals(timerSummary, that.timerSummary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timerSummary);
  }
}
