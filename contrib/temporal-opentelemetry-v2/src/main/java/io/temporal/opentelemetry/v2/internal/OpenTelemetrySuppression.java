package io.temporal.opentelemetry.v2.internal;

import io.temporal.workflow.unsafe.WorkflowUnsafe;

/** Where replayed workflow code must not export telemetry again. */
public final class OpenTelemetrySuppression {
  private OpenTelemetrySuppression() {}

  /**
   * True on a workflow thread that is re-executing history. Query handlers, update validators, and
   * side effect functions run live, at most once, even while the workflow replays, so their
   * telemetry is kept. Await conditions are re-evaluated on every replay and so are suppressed,
   * which is why this asks whether the calling code is subject to replay rather than whether it can
   * mutate workflow state.
   */
  public static boolean shouldSuppress() {
    return WorkflowUnsafe.isWorkflowThread()
        && WorkflowUnsafe.isSubjectToReplay()
        && WorkflowUnsafe.isReplaying();
  }
}
