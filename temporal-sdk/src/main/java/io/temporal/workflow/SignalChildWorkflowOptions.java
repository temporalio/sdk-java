package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

/** Options for {@link ChildWorkflowStub#signal(String, SignalChildWorkflowOptions, Object...)}. */
public final class SignalChildWorkflowOptions {

  public static SignalChildWorkflowOptions.Builder newBuilder() {
    return new SignalChildWorkflowOptions.Builder();
  }

  public static SignalChildWorkflowOptions.Builder newBuilder(SignalChildWorkflowOptions options) {
    return new SignalChildWorkflowOptions.Builder(options);
  }

  public static SignalChildWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SignalChildWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = SignalChildWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(SignalChildWorkflowOptions options) {
      if (options == null) {
        return;
      }
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Event Groups to associate with the signal command, in addition to those in scope. Calls
     * accumulate.
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

    public SignalChildWorkflowOptions build() {
      return new SignalChildWorkflowOptions(eventGroups);
    }
  }

  private final List<EventGroup> eventGroups;

  private SignalChildWorkflowOptions(List<EventGroup> eventGroups) {
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
    return "SignalChildWorkflowOptions{" + "eventGroups=" + eventGroups + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SignalChildWorkflowOptions that = (SignalChildWorkflowOptions) o;
    return Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(eventGroups);
  }
}
