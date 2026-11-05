package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;

/**
 * Options for {@link ExternalWorkflowStub#signal(String, SignalExternalWorkflowOptions,
 * Object...)}.
 */
public final class SignalExternalWorkflowOptions {

  public static SignalExternalWorkflowOptions.Builder newBuilder() {
    return new SignalExternalWorkflowOptions.Builder();
  }

  public static SignalExternalWorkflowOptions.Builder newBuilder(
      SignalExternalWorkflowOptions options) {
    return new SignalExternalWorkflowOptions.Builder(options);
  }

  /**
   * Creates options for signaling an external workflow with the same settings as the provided child
   * workflow signal options.
   */
  public static SignalExternalWorkflowOptions from(SignalChildWorkflowOptions options) {
    Objects.requireNonNull(options, "options");
    List<EventGroup> eventGroups = options.getEventGroups();
    return SignalExternalWorkflowOptions.newBuilder()
        .addEventGroups(eventGroups.toArray(new EventGroup[eventGroups.size()]))
        .build();
  }

  public static SignalExternalWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final SignalExternalWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = SignalExternalWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(SignalExternalWorkflowOptions options) {
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

    public SignalExternalWorkflowOptions build() {
      return new SignalExternalWorkflowOptions(eventGroups);
    }
  }

  private final List<EventGroup> eventGroups;

  private SignalExternalWorkflowOptions(List<EventGroup> eventGroups) {
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
    return "SignalExternalWorkflowOptions{" + "eventGroups=" + eventGroups + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SignalExternalWorkflowOptions that = (SignalExternalWorkflowOptions) o;
    return Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(eventGroups);
  }
}
