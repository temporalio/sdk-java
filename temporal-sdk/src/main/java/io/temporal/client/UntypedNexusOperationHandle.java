package io.temporal.client;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * An untyped handle to a standalone Nexus operation execution. Use this to get the result,
 * describe, cancel, or terminate the operation when the result type is not known at compile time.
 *
 * <p>Obtain an instance via {@link NexusClient#getHandle(String)} or as the untyped projection of a
 * handle returned by {@link NexusServiceClient}.
 *
 * @see NexusOperationHandle
 * @see NexusClient
 */
@Experimental
public interface UntypedNexusOperationHandle {

  /** The caller-assigned operation ID for this execution. Always non-null. */
  String getNexusOperationId();

  /**
   * The server-assigned run ID for this operation execution. Present when the handle was returned
   * by {@code start} or when {@link NexusClient#getHandle(String, String)} was called with an
   * explicit run ID. May be {@code null} when obtained via {@link NexusClient#getHandle(String)}
   * without a run ID — call {@link #describe()} to retrieve the current run ID.
   */
  @Nullable
  String getNexusOperationRunId();

  /**
   * Blocks until the standalone Nexus operation completes and returns the typed result. Polls the
   * server via long-polling.
   *
   * @param resultClass the class to deserialize the result into
   * @throws NexusOperationException if the operation failed, timed out, or was cancelled; the
   *     concrete subtype reflects the underlying failure
   */
  <R> R getResult(Class<R> resultClass);

  /**
   * Blocks until the standalone Nexus operation completes and returns the typed result. Use this
   * overload for generic return types (e.g. {@code List<String>}).
   *
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization; may be {@code null}
   * @throws NexusOperationException if the operation failed, timed out, or was cancelled; the
   *     concrete subtype reflects the underlying failure
   */
  <R> R getResult(Class<R> resultClass, @Nullable Type resultType);

  /**
   * Blocks until the standalone Nexus operation completes and returns the typed result, or throws
   * if the client-side timeout expires before the operation completes.
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   * @param resultClass the class to deserialize the result into
   * @throws NexusOperationException if the operation failed, timed out on the server, or was
   *     cancelled
   * @throws TimeoutException if the client-side {@code timeout} expires before the operation
   *     completes
   */
  <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass) throws TimeoutException;

  /**
   * Blocks until the standalone Nexus operation completes and returns the typed result, or throws
   * if the client-side timeout expires. Use this overload for generic return types (e.g. {@code
   * List<String>}).
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization; may be {@code null}
   * @throws NexusOperationException if the operation failed, timed out on the server, or was
   *     cancelled
   * @throws TimeoutException if the client-side {@code timeout} expires before the operation
   *     completes
   */
  <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType)
      throws TimeoutException;

  /**
   * Returns a future that completes when the operation completes and resolves to the typed result.
   *
   * @param resultClass the class to deserialize the result into
   */
  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass);

  /**
   * Returns a future that completes when the operation completes and resolves to the typed result.
   * Use this overload for generic return types (e.g. {@code List<String>}).
   *
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization; may be {@code null}
   */
  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType);

  /**
   * Returns a future that completes when the operation completes, or fails with {@link
   * TimeoutException} if the operation does not complete within the specified timeout.
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   * @param resultClass the class to deserialize the result into
   */
  <R> CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit, Class<R> resultClass);

  /**
   * Returns a future for generic return types with a timeout.
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization; may be {@code null}
   */
  <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType);

  /**
   * Describes the current state of the Nexus operation execution.
   *
   * @return detailed information about the operation
   */
  NexusOperationExecutionDescription describe();

  /** Requests cancellation of the Nexus operation. */
  void cancel();

  /**
   * Requests cancellation of the Nexus operation with an optional reason.
   *
   * @param reason human-readable reason for cancellation, may be {@code null}
   */
  void cancel(@Nullable String reason);

  /** Terminates the Nexus operation immediately, regardless of its current state. */
  void terminate();

  /**
   * Terminates the Nexus operation immediately with a reason.
   *
   * @param reason human-readable reason for termination, may be {@code null}
   */
  void terminate(@Nullable String reason);
}
