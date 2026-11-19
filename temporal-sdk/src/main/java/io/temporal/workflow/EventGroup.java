package io.temporal.workflow;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An Event Group associates related workflow commands, so that their resulting history events can
 * be presented together in UI/CLI.
 *
 * <p>Event Groups are created with {@link Workflow#newEventGroup(String)} or {@link
 * Workflow#newEventGroup(String, EventGroupOptions)}, then attached to commands through the {@code
 * addEventGroups} method of the corresponding options builder.
 *
 * <p>Groups are identified by their ID. When several groups with the same ID are attached to a
 * single command, they are recorded only once, and the label of the first one is used.
 */
@Experimental
public interface EventGroup {

  /** ID of this group, as provided to {@link Workflow#newEventGroup}. */
  @Nonnull
  String getId();

  /** Label of this group, as provided through {@link EventGroupOptions#getLabel()}. */
  @Nullable
  String getLabel();
}
