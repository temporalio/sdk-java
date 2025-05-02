package io.temporal.internal.worker;

import io.grpc.Deadline;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface LocalActivityDispatcher {
  /**
   * Synchronously dispatches the local activity to the local activity worker.
   *
   * @return true if the local activity was accepted, false if it was rejected
   * @throws IllegalStateException if the local activity worker was not started
   * @throws IllegalArgumentException if the local activity type is not supported
   */
  boolean dispatch(
      @Nonnull ExecuteLocalActivityParameters params,
      @Nonnull Functions.Proc1<LocalActivityResult> resultCallback,
      @Nullable Deadline acceptanceDeadline);
}
