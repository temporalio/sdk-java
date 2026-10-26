package io.temporal.opentelemetry.v2.internal;

import io.temporal.workflow.unsafe.WorkflowUnsafe;

/** Where replayed workflow code must not export telemetry again. */
public final class OpenTelemetrySuppression {
  private OpenTelemetrySuppression() {}

  /**
   * True on a workflow thread that is re-executing history. Read-only code, such as query handlers,
   * update validators, and await conditions, is never suppressed. It does not run a deterministic
   * number of times, so its telemetry is emitted every time, with IDs that do not come from a
   * random stream.
   */
  public static boolean shouldSuppress() {
    return !WorkflowUnsafe.isReadOnly() && WorkflowUnsafe.isReplaying();
  }
}
