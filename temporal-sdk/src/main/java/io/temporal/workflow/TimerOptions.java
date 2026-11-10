package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

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
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(TimerOptions options) {
      if (options == null) {
        return;
      }
      this.summary = options.summary;
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Single-line fixed summary for this timer that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    public Builder setSummary(String summary) {
      this.summary = summary;
      return this;
    }

    /**
     * Event Groups to associate with this timer, in addition to those in scope. Calls accumulate.
     *
     * @see EventGroup
     */
    @Experimental
    public Builder addEventGroups(EventGroup... eventGroups) {
      if (eventGroups.length == 0) return this;
      if (this.eventGroups == null) {
        this.eventGroups = new ArrayList<>(eventGroups.length);
      }
      for (EventGroup group : eventGroups) {
        this.eventGroups.add(Objects.requireNonNull(group, "eventGroups"));
      }
      return this;
    }

    public TimerOptions build() {
      return new TimerOptions(summary, eventGroups);
    }
  }

  private final String summary;
  private final List<EventGroup> eventGroups;

  private TimerOptions(String summary, List<EventGroup> eventGroups) {
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

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "TimerOptions{" + "summary='" + summary + '\'' + ", eventGroups=" + eventGroups + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TimerOptions that = (TimerOptions) o;
    return Objects.equals(summary, that.summary) && Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hash(summary, eventGroups);
  }
}
