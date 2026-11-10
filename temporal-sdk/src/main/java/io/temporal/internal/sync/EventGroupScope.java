package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.common.converter.DataConverter;
import io.temporal.workflow.EventGroup;
import io.temporal.workflow.Functions;
import java.util.*;

/**
 * The Event Groups that apply to commands issued from the current workflow thread, because they
 * were entered with {@link EventGroup#run}.
 */
final class EventGroupScope {
  static final EventGroupScope EMPTY = new EventGroupScope(Collections.emptyMap());

  private static final ThreadLocal<EventGroupScope> currentScope =
      ThreadLocal.withInitial(() -> EMPTY);

  static EventGroupScope current() {
    return currentScope.get();
  }

  /** Keyed by group ID, to make merging scopes easier. */
  private final Map<String, EventGroupImpl> explicitGroups;

  private EventGroupScope(Map<String, EventGroupImpl> explicitGroups) {
    this.explicitGroups = explicitGroups;
  }

  EventGroupScope withExplicitGroups(Collection<EventGroup> additionalGroups) {
    if (additionalGroups.isEmpty()) {
      return this;
    }
    Map<String, EventGroupImpl> merged = new LinkedHashMap<>(explicitGroups);
    for (EventGroup group : additionalGroups) {
      Preconditions.checkArgument(
          group instanceof EventGroupImpl,
          "Event groups must be created with Workflow.newEventGroup, got %s",
          group);
      merged.putIfAbsent(group.getId(), (EventGroupImpl) group);
    }
    return new EventGroupScope(merged);
  }

  /** Run the function with this scope as the current one. */
  <R> R run(Functions.Func<R> func) {
    EventGroupScope previous = current();
    try {
      currentScope.set(this);
      return func.apply();
    } finally {
      EventGroupScope after = currentScope.get();
      currentScope.set(previous);
      if (after != this) {
        throw new Error("Unexpected Event Group Scope on exit");
      }
    }
  }

  /**
   * @param directGroups groups attached directly to the command, through its options
   * @param dataConverter converter used to encode labels, including any payload codec
   */
  List<EventGroupMarker> toMarkers(
      Collection<EventGroup> directGroups, DataConverter dataConverter) {
    EventGroupScope effective = withExplicitGroups(directGroups);
    if (effective.explicitGroups.isEmpty()) {
      return Collections.emptyList();
    }
    List<EventGroupMarker> markers = new ArrayList<>(effective.explicitGroups.size());
    for (EventGroupImpl group : effective.explicitGroups.values()) {
      markers.add(group.toMarker(dataConverter));
    }
    return markers;
  }
}
