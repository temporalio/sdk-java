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
 */
@Experimental
public interface NexusServiceClient<T> extends UntypedNexusServiceClient {

  static <T> NexusServiceClient<T> newInstance(
      Class<T> service, String endpoint, WorkflowServiceStubs stubs) {
    return newInstance(service, endpoint, stubs, NexusClientOptions.getDefaultInstance());
  }

  static <T> NexusServiceClient<T> newInstance(
      Class<T> service, String endpoint, WorkflowServiceStubs stubs, NexusClientOptions options) {
    return NexusServiceClientImpl.newInstance(service, endpoint, stubs, options);
  }

  /**
   * Execute an operation synchronously. Equivalent to {@link #start(BiFunction, Object)} followed
   * by {@link NexusClientHandle#getResult()}.
   */
  default <U, R> R execute(BiFunction<T, U, R> operation, U input) {
    return start(operation, input).getResult();
  }

  /** Execute an operation synchronously with per-call options. */
  default <U, R> R execute(
      BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options) {
    return start(operation, input, options).getResult();
  }

  /** Start an operation and return a typed handle to track its execution. */
  default <U, R> NexusClientHandle<R> start(BiFunction<T, U, R> operation, U input) {
    return start(operation, input, StartNexusOperationOptions.getDefaultInstance());
  }

  /** Start an operation with per-call options and return a typed handle. */
  <U, R> NexusClientHandle<R> start(
      BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options);

  /**
   * Async variant of {@link #execute(BiFunction, Object)}. Returns a {@link CompletableFuture} that
   * completes with the typed result, or completes exceptionally if the operation fails.
   */
  default <U, R> CompletableFuture<R> executeAsync(BiFunction<T, U, R> operation, U input) {
    return start(operation, input).getResultAsync();
  }

  /** Async variant of {@link #execute(BiFunction, Object, StartNexusOperationOptions)}. */
  default <U, R> CompletableFuture<R> executeAsync(
      BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options) {
    return start(operation, input, options).getResultAsync();
  }
}
