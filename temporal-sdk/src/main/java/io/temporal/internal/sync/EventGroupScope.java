package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.common.converter.DataConverter;
import io.temporal.workflow.EventGroup;
import io.temporal.workflow.Functions;
import java.util.*;
import javax.annotation.Nullable;

/**
 * The Event Groups that apply to commands issued from the current workflow thread, either because
 * they were entered with {@link EventGroup#run}, or implicitly because the thread runs a signal or
 * update handler.
 */
final class EventGroupScope {
  static final EventGroupScope EMPTY = new EventGroupScope(Collections.emptyMap(), null);

  private static final ThreadLocal<EventGroupScope> currentScope =
      ThreadLocal.withInitial(() -> EMPTY);

  static EventGroupScope current() {
    return currentScope.get();
  }

  static <R> R runInInboundEventScope(long eventId, Functions.Func<R> func) {
    EventGroupMarker marker =
        EventGroupMarker.newBuilder()
            .setInboundEvent(EventGroupMarker.InboundEvent.newBuilder().setInboundEventId(eventId))
            .build();

    // Runtime-registered handlers do not inherit scoped EGs from the thread that registered them
    return new EventGroupScope(Collections.emptyMap(), marker).run(func);
  }

  static <R> R runInInboundUpdateScope(String updateId, Functions.Func<R> func) {
    EventGroupMarker marker =
        EventGroupMarker.newBuilder()
            .setInboundUpdate(
                EventGroupMarker.InboundUpdate.newBuilder().setInboundUpdateId(updateId))
            .build();

    // Runtime-registered handlers do not inherit scoped EGs from the thread that registered them
    return new EventGroupScope(Collections.emptyMap(), marker).run(func);
  }

  /** Keyed by group ID, to make merging scopes easier. */
  private final Map<String, EventGroupImpl> explicitGroups;

  private final @Nullable EventGroupMarker implicitGroup;

  private EventGroupScope(
      Map<String, EventGroupImpl> explicitGroups, @Nullable EventGroupMarker implicitGroup) {
    this.explicitGroups = explicitGroups;
    this.implicitGroup = implicitGroup;
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
    return new EventGroupScope(merged, implicitGroup);
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
    if (effective.explicitGroups.isEmpty() && effective.implicitGroup == null) {
      return Collections.emptyList();
    }
    List<EventGroupMarker> markers = new ArrayList<>(effective.explicitGroups.size() + 1);
    for (EventGroupImpl group : effective.explicitGroups.values()) {
      markers.add(group.toMarker(dataConverter));
    }
    if (effective.implicitGroup != null) {
      markers.add(effective.implicitGroup);
    }
    return markers;
  }
}
