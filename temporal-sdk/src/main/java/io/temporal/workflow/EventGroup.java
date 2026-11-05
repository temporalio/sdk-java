package io.temporal.workflow;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An Event Group associates related workflow commands, so that their resulting history events can
 * be presented together in UI/CLI.
 *
 * <p>Event Groups are created with {@link Workflow#newEventGroup(String)} or {@link
 * Workflow#newEventGroup(String, EventGroupOptions)}. A group can then be attached to commands
 * either directly, through the {@code addEventGroups} method of the corresponding options builder
 * (e.g. {@link io.temporal.activity.ActivityOptions.Builder#addEventGroups}), or
 * through scope propagation, by issuing commands from within {@link #run(Functions.Proc)}.
 *
 * <p>Groups are identified by their ID. When several groups with the same ID apply to a single
 * command, they are recorded only once, and the label of the outermost scope, or of the first group
 * directly attached, is used.
 */
@Experimental
public interface EventGroup {

  /** ID of this group, as provided to {@link Workflow#newEventGroup}. */
  @Nonnull
  String getId();

  /** Label of this group, as provided through {@link EventGroupOptions#getLabel()}. */
  @Nullable
  String getLabel();

  /**
   * Runs the given procedure with this group in scope. All commands issued from within the
   * procedure, including those issued from workflow threads started from within it, are associated
   * with this group. Scopes may be nested, in which case commands are associated with all enclosing
   * groups.
   */
  void run(Functions.Proc proc);

  /**
   * Runs the given function with this group in scope, and returns its result. All commands issued
   * from within the function, including those issued from workflow threads started from within it,
   * are associated with this group. Scopes may be nested, in which case commands are associated
   * with all enclosing groups.
   */
  <R> R run(Functions.Func<R> func);
}
