package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

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
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(MutableSideEffectOptions options) {
      if (options == null) {
        return;
      }
      this.summary = options.summary;
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Single-line fixed summary for this mutable side effect that will appear in UI/CLI. This can
     * be in single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    public MutableSideEffectOptions.Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    /**
     * Event Groups to associate with the marker recorded by this call, in addition to those in
     * scope. Calls accumulate.
     *
     * @see EventGroup
     */
    @Experimental
    public MutableSideEffectOptions.Builder addEventGroups(EventGroup... eventGroups) {
      if (eventGroups.length == 0) return this;
      if (this.eventGroups == null) {
        this.eventGroups = new ArrayList<>(eventGroups.length);
      }
      for (EventGroup group : eventGroups) {
        this.eventGroups.add(Objects.requireNonNull(group, "eventGroups"));
      }
      return this;
    }

    public MutableSideEffectOptions build() {
      return new MutableSideEffectOptions(summary, eventGroups);
    }
  }

  private final String summary;
  private final List<EventGroup> eventGroups;

  private MutableSideEffectOptions(String summary, List<EventGroup> eventGroups) {
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

  public MutableSideEffectOptions.Builder toBuilder() {
    return new MutableSideEffectOptions.Builder(this);
  }

  @Override
  public String toString() {
    return "MutableSideEffectOptions{"
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
    MutableSideEffectOptions that = (MutableSideEffectOptions) o;
    return Objects.equals(summary, that.summary) && Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary, eventGroups);
  }
}
