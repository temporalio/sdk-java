package io.temporal.activity;

import io.temporal.internal.activity.ActivityInternal;
import io.temporal.internal.sync.WorkflowInternal;

/**
 * An Activity is the implementation of a particular task in the business logic.
 *
 * @see io.temporal.worker.Worker
 * @see io.temporal.workflow.Workflow
 * @see io.temporal.client.WorkflowClient
 */
public final class Activity {

  /**
   * Can be used to get information about an Activity Execution and to invoke Heartbeats. This
   * static method relies on a thread-local variable and works only in the original Activity
   * Execution thread.
   */
  public static ActivityExecutionContext getExecutionContext() {
    return ActivityInternal.getExecutionContext();
  }

  /**
   * Use this to rethrow a checked exception from an Activity Execution instead of adding the
   * exception to a method signature.
   *
   * @return Never returns; always throws. Throws original exception if e is {@link
   *     RuntimeException} or {@link Error}.
   */
  public static RuntimeException wrap(Throwable e) {
    return WorkflowInternal.wrap(e);
  }

  /** Prohibits instantiation. */
  private Activity() {}
}
