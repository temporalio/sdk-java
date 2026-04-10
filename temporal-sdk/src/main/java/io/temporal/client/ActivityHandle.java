package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * A handle to a standalone activity execution started via {@link WorkflowClient#startActivity}. Use
 * this to get the result, describe, cancel, or terminate the activity.
 *
 * <p>Obtain an instance via {@link WorkflowClient#startActivity} or {@link
 * WorkflowClient#getActivityHandle(String)}.
 */
@Experimental
public interface ActivityHandle {

  /** The user-assigned activity ID. */
  String getActivityId();

  /**
   * The server-assigned run ID returned when the activity was started. May be {@code null} when
   * obtained via {@link WorkflowClient#getActivityHandle(String)} without a run ID.
   */
  @Nullable
  String getActivityRunId();

  /**
   * Blocks until the standalone activity completes, then returns. Polls the server via
   * long-polling.
   *
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  void getResult() throws ActivityFailedException;

  /**
   * Blocks until the standalone activity completes, then returns the typed result. Polls the server
   * via long-polling.
   *
   * @param resultClass the class to deserialize the result into
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  <R> R getResult(Class<R> resultClass) throws ActivityFailedException;

  /**
   * Describes the current state of the activity execution.
   *
   * @return detailed information about the activity
   */
  ActivityExecutionDescription describe();

  /**
   * Describes the current state of the activity execution, with options.
   *
   * @param options describe options (e.g. long-poll token for change notification)
   * @return detailed information about the activity
   */
  ActivityExecutionDescription describe(ActivityDescribeOptions options);

  /**
   * Requests cancellation of the activity. The activity will receive a {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} heartbeat failure with a
   * {@link io.temporal.failure.CanceledFailure}.
   */
  void cancel();

  /**
   * Requests cancellation of the activity with options.
   *
   * @param options cancellation options such as a reason string
   */
  void cancel(ActivityCancelOptions options);

  /**
   * Terminates the activity immediately, regardless of its current state.
   *
   * @param reason human-readable reason for termination, may be {@code null}
   */
  void terminate(@Nullable String reason);

  /**
   * Terminates the activity immediately with options.
   *
   * @param reason human-readable reason for termination, may be {@code null}
   * @param options termination options
   */
  void terminate(@Nullable String reason, ActivityTerminateOptions options);
}
