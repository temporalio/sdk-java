package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Typed client for invoking standalone Nexus operations on a specific service interface {@code T}.
 *
 * <p>Operations are dispatched via method references (or {@link BiFunction} lambdas) that target
 * methods on {@code T}; the client extracts the operation name from the invocation and delegates to
 * {@link NexusClient}. For visibility queries (list/count) across operations, use {@link
 * NexusClient} directly.
 *
 * @param <T> the Nexus service interface this client is bound to
 * @see NexusClient
 * @see UntypedNexusServiceClient
 */
@Experimental
public interface NexusServiceClient<T> extends UntypedNexusServiceClient {

  /**
   * Creates a client bound to {@code service} that dispatches calls via {@code endpoint} using
   * default client options.
   *
   * @param service the Nexus service interface class
   * @param endpoint the Nexus endpoint name as configured on the server
   * @param stubs gRPC stubs for talking to the Temporal service
   * @return a new typed client
   */
  static <T> NexusServiceClient<T> newInstance(
      Class<T> service, String endpoint, WorkflowServiceStubs stubs) {
    return newInstance(service, endpoint, stubs, NexusClientOptions.getDefaultInstance());
  }

  /**
   * Creates a client bound to {@code service} that dispatches calls via {@code endpoint} using the
   * supplied client options.
   *
   * @param service the Nexus service interface class
   * @param endpoint the Nexus endpoint name as configured on the server
   * @param stubs gRPC stubs for talking to the Temporal service
   * @param options client-wide options (namespace, identity, interceptors, etc.)
   * @return a new typed client
   */
  static <T> NexusServiceClient<T> newInstance(
      Class<T> service, String endpoint, WorkflowServiceStubs stubs, NexusClientOptions options) {
    return NexusServiceClientImpl.newInstance(service, endpoint, stubs, options);
  }

  /**
   * Executes an operation synchronously with per-call options.
   *
   * @param operation a method reference on {@code T} identifying the operation
   * @param input the operation input
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @return the operation result
   * @throws RuntimeException if the operation failed, timed out, or was cancelled
   */
  <U, R> R execute(BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options);

  /**
   * Starts an operation with per-call options and returns a typed handle.
   *
   * @param operation a method reference on {@code T} identifying the operation
   * @param input the operation input
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @return a typed handle bound to the started operation
   */
  <U, R> NexusOperationHandle<R> start(
      BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options);

  /**
   * Async variant of {@link #execute(BiFunction, Object, StartNexusOperationOptions)}. Returns a
   * {@link CompletableFuture} that completes with the typed result, or completes exceptionally if
   * the operation fails.
   *
   * @param operation a method reference on {@code T} identifying the operation
   * @param input the operation input
   * @param options per-call options controlling timeouts, search attributes, etc.
   */
  <U, R> CompletableFuture<R> executeAsync(
      BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options);
}
