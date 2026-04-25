package io.temporal.client;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * An untyped handle to a standalone activity execution. Use this to get the result, describe,
 * cancel, or terminate the activity when the result type is not known at compile time.
 *
 * <p>Obtain an instance via {@link ActivityClient#start(String, StartActivityOptions, Object...)}
 * or {@link ActivityClient#getHandle(String, String)}.
 *
 * @see ActivityHandle
 * @see ActivityClient
 */
@Experimental
public interface UntypedActivityHandle {

  /** The user-assigned activity ID. */
  String getActivityId();

  /**
   * The server-assigned run ID for this execution. Present when the handle was returned by {@link
   * ActivityClient#start}. May be {@code null} when obtained via {@link
   * ActivityClient#getHandle(String, String)} with a {@code null} run ID — call {@link #describe()}
   * to retrieve the current run ID.
   */
  @Nullable
  String getActivityRunId();

  /**
   * Blocks until the standalone activity completes and returns the typed result. Polls the server
   * via long-polling.
   *
   * @param resultClass the class to deserialize the result into
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  <R> R getResult(Class<R> resultClass) throws ActivityFailedException;

  /**
   * Blocks until the standalone activity completes and returns the typed result. Use this overload
   * for generic return types (e.g. {@code List<String>}).
   *
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization; may be {@code null}
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  <R> R getResult(Class<R> resultClass, @Nullable Type resultType) throws ActivityFailedException;

  /**
   * Returns a future that completes when the activity completes and resolves to the typed result.
   *
   * @param resultClass the class to deserialize the result into
   */
  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass);

  /**
   * Returns a future for generic return types (e.g. {@code List<String>}).
   *
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization; may be {@code null}
   */
  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType);

  /**
   * Describes the current state of the activity execution.
   *
   * @return detailed information about the activity
   */
  ActivityExecutionDescription describe();

  /**
   * Long-polls until the activity state changes from the state encoded in {@code longPollToken},
   * then returns the updated description. Pass the token from a previous {@link #describe()} call
   * via {@link ActivityExecutionDescription#getLongPollToken()}. If {@code longPollToken} is {@code
   * null}, returns the current state immediately (equivalent to {@link #describe()}).
   *
   * @param longPollToken token from a previous describe response, or {@code null} for an immediate
   *     snapshot
   * @return updated description of the activity
   */
  ActivityExecutionDescription describe(@Nullable byte[] longPollToken);

  /**
   * Requests cancellation of the activity. The activity will receive a cancellation via {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)}.
   */
  void cancel();

  /**
   * Requests cancellation of the activity with an optional reason.
   *
   * @param reason human-readable reason for cancellation, may be {@code null}
   */
  void cancel(@Nullable String reason);

  /** Terminates the activity immediately, regardless of its current state. */
  void terminate();

  /**
   * Terminates the activity immediately with a reason.
   *
   * @param reason human-readable reason for termination, may be {@code null}
   */
  void terminate(@Nullable String reason);
}
