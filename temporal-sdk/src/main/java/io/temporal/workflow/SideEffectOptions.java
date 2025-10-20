package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Objects;

/** SideEffectOptions is used to specify options for a side effect. */
public class SideEffectOptions {

  public static Builder newBuilder() {
    return new SideEffectOptions.Builder();
  }

  public static Builder newBuilder(SideEffectOptions options) {
    return new SideEffectOptions.Builder(options);
  }

  public static SideEffectOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SideEffectOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = SideEffectOptions.newBuilder().build();
  }

  public static final class Builder {
    private String summary;

    private Builder() {}

    private Builder(SideEffectOptions options) {
      if (options == null) {
        return;
      }
      this.summary = options.summary;
    }

    /**
     * Single-line fixed summary for this side effect that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public SideEffectOptions.Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    public SideEffectOptions build() {
      return new SideEffectOptions(summary);
    }
  }

  private final String summary;

  private SideEffectOptions(String summary) {
    this.summary = summary;
  }

  public String getSummary() {
    return summary;
  }

  public SideEffectOptions.Builder toBuilder() {
    return new SideEffectOptions.Builder(this);
  }

  @Override
  public String toString() {
    return "SideEffectOptions{" + "summary='" + summary + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SideEffectOptions that = (SideEffectOptions) o;
    return Objects.equals(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary);
  }
}
