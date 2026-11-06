package io.temporal.workflow;

import io.temporal.common.Experimental;
import io.temporal.common.SearchAttributeUpdate;
import java.util.*;

/**
 * Options for {@link Workflow#upsertTypedSearchAttributes(UpsertSearchAttributesOptions,
 * SearchAttributeUpdate[])}.
 */
public final class UpsertSearchAttributesOptions {

  public static UpsertSearchAttributesOptions.Builder newBuilder() {
    return new UpsertSearchAttributesOptions.Builder();
  }

  public static UpsertSearchAttributesOptions.Builder newBuilder(
      UpsertSearchAttributesOptions options) {
    return new UpsertSearchAttributesOptions.Builder(options);
  }

  public static UpsertSearchAttributesOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final UpsertSearchAttributesOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = UpsertSearchAttributesOptions.newBuilder().build();
  }

  public static final class Builder {
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(UpsertSearchAttributesOptions options) {
      if (options == null) {
        return;
      }
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Event Groups to associate with the search attributes update. Calls accumulate.
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

    public UpsertSearchAttributesOptions build() {
      return new UpsertSearchAttributesOptions(eventGroups);
    }
  }

  private final List<EventGroup> eventGroups;

  private UpsertSearchAttributesOptions(List<EventGroup> eventGroups) {
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
    return "UpsertSearchAttributesOptions{" + "eventGroups=" + eventGroups + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    UpsertSearchAttributesOptions that = (UpsertSearchAttributesOptions) o;
    return Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(eventGroups);
  }
}
