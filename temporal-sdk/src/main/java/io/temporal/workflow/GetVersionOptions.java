package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

/** Options for {@link Workflow#getVersion(String, int, int, GetVersionOptions)}. */
public final class GetVersionOptions {

  public static GetVersionOptions.Builder newBuilder() {
    return new GetVersionOptions.Builder();
  }

  public static GetVersionOptions.Builder newBuilder(GetVersionOptions options) {
    return new GetVersionOptions.Builder(options);
  }

  public static GetVersionOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final GetVersionOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = GetVersionOptions.newBuilder().build();
  }

  public static final class Builder {
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(GetVersionOptions options) {
      if (options == null) {
        return;
      }
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Event Groups to associate with the version marker. Calls accumulate.
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

    /**
     * Associates the given Event Groups, in addition to any already added. Calls accumulate.
     *
     * @see EventGroup
     */
    @Experimental
    public Builder addEventGroups(List<EventGroup> eventGroups) {
      return addEventGroups(eventGroups.toArray(new EventGroup[0]));
    }

    public GetVersionOptions build() {
      return new GetVersionOptions(eventGroups);
    }
  }

  private final List<EventGroup> eventGroups;

  private GetVersionOptions(List<EventGroup> eventGroups) {
    this.eventGroups =
        eventGroups == null || eventGroups.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(eventGroups));
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
    return "GetVersionOptions{" + "eventGroups=" + eventGroups + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GetVersionOptions that = (GetVersionOptions) o;
    return Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(eventGroups);
  }
}
