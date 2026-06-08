package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Typed client for invoking standalone Nexus operations on a specific service interface {@code T}.
 *
 * <p>Operations are dispatched via method references on {@code T} (or equivalent {@link BiFunction}
 * / {@link Function} lambdas); the client extracts the operation name from the invocation and
 * delegates to {@link NexusClient}. For visibility queries (list/count) across operations, use
 * {@link NexusClient} directly.
 *
 * <h2>Usage</h2>
 *
 * <p>Given a Nexus service interface:
 *
 * <pre>{@code
 * @Service
 * public interface GreeterService {
 *   @Operation String greet(String name);     // input + output
 *   @Operation String now();                  // no input, output
 *   @Operation Void log(String message);      // input, no output
 * }
 * }</pre>
 *
 * <p>Build a client and dispatch by method reference:
 *
 * <pre>{@code
 * NexusClient nexusClient = NexusClient.newInstance(workflowServiceStubs);
 * NexusServiceClient<GreeterService> client =
 *     nexusClient.newNexusServiceClient(GreeterService.class, "greeter-endpoint");
 *
 * StartNexusOperationOptions options = StartNexusOperationOptions.newBuilder()
 *     .setId(UUID.randomUUID().toString())
 *     .build();
 *
 * // Operation that takes an input (BiFunction overload):
 * String hi = client.execute(GreeterService::greet, "Ada", options);
 *
 * // Operation with no input (Function overload):
 * String t = client.execute(GreeterService::now, options);
 *
 * // Operation that returns Void: the same overloads work, R is just Void.
 * client.execute(GreeterService::log, "hello", options);
 *
 * // Get a handle instead of blocking:
 * NexusOperationHandle<String> handle = client.start(GreeterService::greet, "Ada", options);
 * String result = handle.getResult();
 *
 * // Run asynchronously:
 * CompletableFuture<String> future =
 *     client.executeAsync(GreeterService::greet, "Ada", options);
 * }</pre>
 *
 * @param <T> the Nexus service interface this client is bound to
 * @see NexusClient
 * @see UntypedNexusServiceClient
 */
@Experimental
public interface NexusServiceClient<T> extends UntypedNexusServiceClient {

  /**
   * Executes an operation synchronously with per-call options.
   *
   * @param operation a method reference on {@code T} identifying the operation
   * @param input the operation input
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @return the operation result
   * @throws NexusOperationException if the operation failed, timed out, or was cancelled
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

  /**
   * Executes a no-input operation synchronously with per-call options. Use this overload for Nexus
   * operations declared without an input parameter on {@code T} (e.g. {@code R operation()}).
   *
   * @param operation a method reference on {@code T} identifying the no-input operation
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @return the operation result
   * @throws NexusOperationException if the operation failed, timed out, or was cancelled
   */
  <R> R execute(Function<T, R> operation, StartNexusOperationOptions options);

  /**
   * Starts a no-input operation with per-call options and returns a typed handle. Use this overload
   * for Nexus operations declared without an input parameter on {@code T}.
   *
   * @param operation a method reference on {@code T} identifying the no-input operation
   * @param options per-call options controlling timeouts, search attributes, etc.
   * @return a typed handle bound to the started operation
   */
  <R> NexusOperationHandle<R> start(Function<T, R> operation, StartNexusOperationOptions options);

  /**
   * Async variant of {@link #execute(Function, StartNexusOperationOptions)} for no-input
   * operations. Returns a {@link CompletableFuture} that completes with the typed result, or
   * completes exceptionally if the operation fails.
   *
   * @param operation a method reference on {@code T} identifying the no-input operation
   * @param options per-call options controlling timeouts, search attributes, etc.
   */
  <R> CompletableFuture<R> executeAsync(
      Function<T, R> operation, StartNexusOperationOptions options);
}
