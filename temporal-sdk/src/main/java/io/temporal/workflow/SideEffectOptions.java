package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

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
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(SideEffectOptions options) {
      if (options == null) {
        return;
      }
      this.summary = options.summary;
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Single-line fixed summary for this side effect that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    public SideEffectOptions.Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    /**
     * Event Groups to associate with this side effect. Calls accumulate.
     *
     * @see EventGroup
     */
    @Experimental
    public SideEffectOptions.Builder addEventGroups(EventGroup... eventGroups) {
      if (eventGroups.length == 0) return this;
      if (this.eventGroups == null) {
        this.eventGroups = new ArrayList<>(eventGroups.length);
      }
      for (EventGroup group : eventGroups) {
        this.eventGroups.add(Objects.requireNonNull(group, "eventGroups"));
      }
      return this;
    }

    public SideEffectOptions build() {
      return new SideEffectOptions(summary, eventGroups);
    }
  }

  private final String summary;
  private final List<EventGroup> eventGroups;

  private SideEffectOptions(String summary, List<EventGroup> eventGroups) {
    this.summary = summary;
    this.eventGroups =
        eventGroups == null || eventGroups.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(eventGroups));
  }

  public String getSummary() {
    return summary;
  }

  @Experimental
  public List<EventGroup> getEventGroups() {
    return eventGroups;
  }

  public SideEffectOptions.Builder toBuilder() {
    return new SideEffectOptions.Builder(this);
  }

  @Override
  public String toString() {
    return "SideEffectOptions{"
        + "summary='"
        + summary
        + '\''
        + ", eventGroups="
        + eventGroups
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SideEffectOptions that = (SideEffectOptions) o;
    return Objects.equals(summary, that.summary) && Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary, eventGroups);
  }
}
