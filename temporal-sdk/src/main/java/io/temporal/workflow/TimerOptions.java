package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Objects;

/** TimerOptions is used to specify options for a timer. */
public final class TimerOptions {

  public static TimerOptions.Builder newBuilder() {
    return new TimerOptions.Builder();
  }

  public static TimerOptions.Builder newBuilder(TimerOptions options) {
    return new TimerOptions.Builder(options);
  }

  public static TimerOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final TimerOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = TimerOptions.newBuilder().build();
  }

  public static final class Builder {
    private String summary;

    private Builder() {}

    private Builder(TimerOptions options) {
      if (options == null) {
        return;
      }
      this.summary = options.summary;
    }

    /**
     * Single-line fixed summary for this timer that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    public TimerOptions build() {
      return new TimerOptions(summary);
    }
  }

  private final String summary;

  private TimerOptions(String summary) {
    this.summary = summary;
  }

  public String getSummary() {
    return summary;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "TimerOptions{" + "summary='" + summary + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TimerOptions that = (TimerOptions) o;
    return Objects.equals(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary);
  }
}
