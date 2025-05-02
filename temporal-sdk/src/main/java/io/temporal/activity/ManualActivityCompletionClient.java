package io.temporal.activity;

import io.temporal.failure.CanceledFailure;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This client is attached to a specific activity execution and let user report completion
 * (successful, failed or confirm cancellation) and perform heartbeats.
 *
 * <p>May be obtained by calling {@link ActivityExecutionContext#useLocalManualCompletion()}
 */
public interface ManualActivityCompletionClient {

  /**
   * Completes the activity execution successfully.
   *
   * @param result of the activity execution
   */
  void complete(@Nullable Object result);

  /**
   * Completes the activity execution with failure.
   *
   * @param failure the exception to be used as a failure details object
   */
  void fail(@Nonnull Throwable failure);

  /**
   * Records heartbeat for an activity
   *
   * @param details to record with the heartbeat
   */
  void recordHeartbeat(@Nullable Object details) throws CanceledFailure;

  /**
   * Confirms successful cancellation to the server.
   *
   * @param details to record with the cancellation
   */
  void reportCancellation(@Nullable Object details);
}
