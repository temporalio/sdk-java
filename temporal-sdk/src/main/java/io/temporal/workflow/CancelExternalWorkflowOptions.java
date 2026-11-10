package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.util.*;
import javax.annotation.Nullable;

/** Options for {@link ExternalWorkflowStub#cancelWithOptions(CancelExternalWorkflowOptions)}. */
public final class CancelExternalWorkflowOptions {

  public static CancelExternalWorkflowOptions.Builder newBuilder() {
    return new CancelExternalWorkflowOptions.Builder();
  }

  public static CancelExternalWorkflowOptions.Builder newBuilder(
      CancelExternalWorkflowOptions options) {
    return new CancelExternalWorkflowOptions.Builder(options);
  }

  public static CancelExternalWorkflowOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final CancelExternalWorkflowOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = CancelExternalWorkflowOptions.newBuilder().build();
  }

  public static final class Builder {
    private @Nullable String reason;
    private List<EventGroup> eventGroups;

    private Builder() {}

    private Builder(CancelExternalWorkflowOptions options) {
      if (options == null) {
        return;
      }
      this.reason = options.reason;
      if (!options.eventGroups.isEmpty()) {
        this.eventGroups = new ArrayList<>(options.eventGroups);
      }
    }

    /**
     * Reason recorded on the cancellation request.
     *
     * <p>Default is none.
     */
    public Builder setReason(@Nullable String reason) {
      this.reason = reason;
      return this;
    }

    /**
     * Event Groups to associate with the cancellation request command. Calls accumulate.
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

    public CancelExternalWorkflowOptions build() {
      return new CancelExternalWorkflowOptions(reason, eventGroups);
    }
  }

  private final @Nullable String reason;
  private final List<EventGroup> eventGroups;

  private CancelExternalWorkflowOptions(@Nullable String reason, List<EventGroup> eventGroups) {
    this.reason = reason;
    this.eventGroups =
        eventGroups == null || eventGroups.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(eventGroups));
  }

  @Nullable
  public String getReason() {
    return reason;
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
    return "CancelExternalWorkflowOptions{"
        + "reason='"
        + reason
        + '\''
        + ", eventGroups="
        + eventGroups
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CancelExternalWorkflowOptions that = (CancelExternalWorkflowOptions) o;
    return Objects.equals(reason, that.reason) && Objects.equals(eventGroups, that.eventGroups);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reason, eventGroups);
  }
}
