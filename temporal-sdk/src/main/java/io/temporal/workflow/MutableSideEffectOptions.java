package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Objects;

/** MutableSideEffectOptions is used to specify options for a side effect. */
public class MutableSideEffectOptions {

  public static Builder newBuilder() {
    return new MutableSideEffectOptions.Builder();
  }

  public static Builder newBuilder(MutableSideEffectOptions options) {
    return new MutableSideEffectOptions.Builder(options);
  }

  public static MutableSideEffectOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final MutableSideEffectOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = MutableSideEffectOptions.newBuilder().build();
  }

  public static final class Builder {
    private String summary;

    private Builder() {}

    private Builder(MutableSideEffectOptions options) {
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
    public MutableSideEffectOptions.Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    public MutableSideEffectOptions build() {
      return new MutableSideEffectOptions(summary);
    }
  }

  private final String summary;

  private MutableSideEffectOptions(String summary) {
    this.summary = summary;
  }

  public String getSummary() {
    return summary;
  }

  public MutableSideEffectOptions.Builder toBuilder() {
    return new MutableSideEffectOptions.Builder(this);
  }

  @Override
  public String toString() {
    return "TimerOptions{" + "summary='" + summary + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MutableSideEffectOptions that = (MutableSideEffectOptions) o;
    return Objects.equals(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary);
  }
}
