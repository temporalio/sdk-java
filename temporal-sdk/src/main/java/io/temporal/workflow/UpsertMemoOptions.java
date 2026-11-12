package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

/** Options for {@link Workflow#upsertMemo(Map, UpsertMemoOptions)}. */
public final class UpsertMemoOptions {

  public static UpsertMemoOptions.Builder newBuilder() {
    return new UpsertMemoOptions.Builder();
  }

  public static UpsertMemoOptions.Builder newBuilder(UpsertMemoOptions options) {
    return new UpsertMemoOptions.Builder(options);
  }

  public static UpsertMemoOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final UpsertMemoOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = UpsertMemoOptions.newBuilder().build();
  }

  public static final class Builder {
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(UpsertMemoOptions options) {
      if (options == null) {
        return;
      }
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Event Groups to associate with the memo update. Calls accumulate.
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

    public UpsertMemoOptions build() {
      return new UpsertMemoOptions(eventGroups);
    }
  }

  private final List<EventGroup> eventGroups;

  private UpsertMemoOptions(List<EventGroup> eventGroups) {
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
    return "UpsertMemoOptions{" + "eventGroups=" + eventGroups + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    UpsertMemoOptions that = (UpsertMemoOptions) o;
    return Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(eventGroups);
  }
}
