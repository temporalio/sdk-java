package io.temporal.workflow.unsafe;

import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.workflow.Functions;

/**
 * While {@link io.temporal.workflow.Workflow} contains methods exposing the main Temporal Workflow
 * API that is safe to use by majority of our users, this class contains the part of the Workflow
 * API that is discouraged to be used without careful consideration. While APIs in this class may be
 * required for some legitimate scenarios, most users shouldn't be using them. Relying on these
 * methods without careful consideration and understanding of limitations of each method may lead to
 * an unexpected behavior and mistakes.
 *
 * <p>Please reference a documentation for each specific method for more context why this method is
 * considered unsafe to be a part of the main Workflow API.
 */
public final class WorkflowUnsafe {

  /**
   * <b>Warning!</b> <br>
   * The only reasonable usage for this method is writing a library code that has to be used in both
   * workflow and non-workflow code (like an Activity or non-Temporal codebase). This usage is
   * generally discouraged, because mixing of workflow and non-workflow code is error-prone, because
   * workflow code requires considerations for determinism. Writing shared code that is designed to
   * be called from both workflow and non-workflow context may lead to leaking of the code not
   * written with Workflow limitations in mind into Workflow method implementation leading to
   * non-deterministic Workflow implementations.
   *
   * @return true if the current execution happens as a part of workflow method and in a workflow
   *     thread context.
   */
  public static boolean isWorkflowThread() {
    return WorkflowInternal.isWorkflowThread();
  }

  /**
   * <b>Warning!</b> <br>
   * Never make workflow code depend on this flag as it is going to break determinism. The only
   * reasonable uses for this flag is deduping external never failing side effects like logging or
   * metric reporting.
   *
   * @return true if workflow code is being replayed. This method always returns false if called
   *     from a non workflow thread.
   */
  public static boolean isReplaying() {
    return WorkflowInternal.isReplaying();
  }

  /**
   * Runs the supplied procedure in the calling thread with disabled deadlock detection if called
   * from the workflow thread. Does nothing except the procedure execution if called from a
   * non-workflow thread.
   *
   * <p><b>Warning!</b> <br>
   * Never make workflow logic depend on this flag. Workflow code that runs into deadlock detector
   * is implemented incorrectly. The intended use of this execution mode is blocking calls and IO in
   * {@link io.temporal.payload.codec.PayloadCodec}, {@link
   * io.temporal.common.converter.PayloadConverter} or {@link
   * io.temporal.common.interceptors.WorkerInterceptor} implementations.
   *
   * @param proc to run with disabled deadlock detection
   */
  public static void deadlockDetectorOff(Functions.Proc proc) {
    deadlockDetectorOff(
        () -> {
          proc.apply();
          return null;
        });
  }

  /**
   * Runs the supplied function in the calling thread with disabled deadlock detection if called
   * from the workflow thread. Does nothing except the function execution if called from a
   * non-workflow thread.
   *
   * <p><b>Warning!</b> <br>
   * Never make workflow code depend on this flag. Workflow code that runs into deadlock detector is
   * implemented incorrectly (see {@link }). The intended use of this execution mode is blocking
   * calls and IO in {@link io.temporal.payload.codec.PayloadCodec}, {@link
   * io.temporal.common.converter.PayloadConverter} or {@link
   * io.temporal.common.interceptors.WorkerInterceptor} implementations.
   *
   * @param func to run with disabled deadlock detection
   * @return result of {@code func} execution
   * @param <T> type of {@code func} result
   */
  public static <T> T deadlockDetectorOff(Functions.Func<T> func) {
    return WorkflowInternal.deadlockDetectorOff(func);
  }

  /** Prohibit instantiation. */
  private WorkflowUnsafe() {}
}
