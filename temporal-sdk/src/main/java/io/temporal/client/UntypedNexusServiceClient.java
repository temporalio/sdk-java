package io.temporal.client;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

/**
 * Untyped client for invoking standalone Nexus operations by operation-name string. Use this when
 * the operation contract is not available as a Java service interface at compile time. For a typed
 * variant, see {@link NexusServiceClient}.
 *
 * @see NexusServiceClient
 * @see NexusClient
 */
@Experimental
public interface UntypedNexusServiceClient {

  /**
   * Starts a Nexus operation by name and returns an untyped handle for tracking its execution.
   *
   * @param operation the operation name as registered on the service
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @param arg the operation input; may be {@code null}
   * @return an untyped handle bound to the started operation
   */
  UntypedNexusOperationHandle start(
      String operation, StartNexusOperationOptions options, @Nullable Object arg);

  /**
   * Executes a Nexus operation synchronously by name, blocking until it completes.
   *
   * @param operation the operation name as registered on the service
   * @param resultClass the class to deserialize the result into
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @param arg the operation input; may be {@code null}
   * @return the deserialized operation result
   * @throws NexusOperationException if the operation failed, timed out, or was cancelled
   */
  <R> R execute(
      String operation,
      Class<R> resultClass,
      StartNexusOperationOptions options,
      @Nullable Object arg);

  /**
   * Executes a Nexus operation synchronously by name with an explicit generic-result {@link Type}.
   * Use this overload when the result is a generic type whose parameters cannot be captured by
   * {@link Class} alone (e.g. {@code List<String>}).
   *
   * @param operation the operation name as registered on the service
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type to use for deserialization
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @param arg the operation input; may be {@code null}
   * @return the deserialized operation result
   * @throws NexusOperationException if the operation failed, timed out, or was cancelled
   */
  <R> R execute(
      String operation,
      Class<R> resultClass,
      Type resultType,
      StartNexusOperationOptions options,
      @Nullable Object arg);
}
