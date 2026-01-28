package io.temporal.internal.activity;

import io.temporal.activity.ActivityExecutionContext;

/**
 * Thread local store of the context object passed to an activity implementation. Avoid using this
 * class directly.
 *
 * @author fateev
 */
final class CurrentActivityExecutionContext {

  private static final ThreadLocal<ActivityExecutionContext> CURRENT = new ThreadLocal<>();

  /**
   * This is used by activity implementation to get access to the current ActivityExecutionContext
   */
  public static ActivityExecutionContext get() {
    ActivityExecutionContext result = CURRENT.get();
    if (result == null) {
      throw new IllegalStateException(
          "ActivityExecutionContext can be used only inside of activity "
              + "implementation methods and in the same thread that invoked an activity.");
    }
    return result;
  }

  public static boolean isSet() {
    return CURRENT.get() != null;
  }

  public static void set(ActivityExecutionContext context) {
    if (context == null) {
      throw new IllegalArgumentException("null context");
    }
    if (CURRENT.get() != null) {
      throw new IllegalStateException("current already set");
    }
    CURRENT.set(context);
  }

  public static void unset() {
    CURRENT.set(null);
  }

  private CurrentActivityExecutionContext() {}
}
