package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nullable;

/** Options used to create an {@link EventGroup}. See {@link Workflow#newEventGroup}. */
@Experimental
public final class EventGroupOptions {

  public static EventGroupOptions.Builder newBuilder() {
    return new EventGroupOptions.Builder();
  }

  public static EventGroupOptions.Builder newBuilder(EventGroupOptions options) {
    return new EventGroupOptions.Builder(options);
  }

  public static EventGroupOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final EventGroupOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = EventGroupOptions.newBuilder().build();
  }

  public static final class Builder {
    private String label;

    private Builder() {}

    private Builder(EventGroupOptions options) {
      if (options == null) {
        return;
      }
      this.label = options.label;
    }

    /**
     * Human-readable label for the group that will appear in UI/CLI. This can be in single-line
     * Temporal Markdown format.
     *
     * <p>The label is encoded using the workflow's data converter, including any payload codec.
     * When the same group is attached to many commands, its label is recorded on each of them.
     *
     * <p>Default is none/empty.
     */
    public Builder setLabel(@Nullable String label) {
      this.label = label;
      return this;
    }

    public EventGroupOptions build() {
      return new EventGroupOptions(label);
    }
  }

  private final @Nullable String label;

  private EventGroupOptions(@Nullable String label) {
    this.label = label;
  }

  @Nullable
  public String getLabel() {
    return label;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "EventGroupOptions{" + "label='" + label + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    EventGroupOptions that = (EventGroupOptions) o;
    return Objects.equals(label, that.label);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(label);
  }
}
