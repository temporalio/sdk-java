package io.temporal.client;

import io.temporal.workflow.NexusOperationOptions;
import java.util.function.BiFunction;

interface NexusServiceClient<T> extends UntypedNexusServiceClient {
  //    public static NexusClient newInstance();
  //    public static NexusClient newInstance(NexusClientOptions options);

  /**
   * Executes an operation on the Nexus service with the provided input. This method is synchronous
   * and returns the result directly.
   *
   * @param operation The operation method to execute, represented as a BiFunction.
   * @param input The input to the operation.
   * @return The result of the operation.
   */
  <U, R> R execute(BiFunction<T, U, R> operation, U input);

  /**
   * Executes an operation on the Nexus service with the provided input. This method is synchronous
   * and returns the result directly.
   *
   * @param operation The operation method to execute, represented as a BiFunction.
   * @param input The input to the operation.
   * @param options for execute operations
   * @return The result of the operation.
   */
  <U, R> R execute(BiFunction<T, U, R> operation, U input, NexusOperationOptions options);

  /**
   * Starts an operation on the Nexus service with the provided input.
   *
   * @param operation The operation method to start, represented as a BiFunction.
   * @param input The input to the operation.
   */
  <U, R> NexusClientHandle<R> start(BiFunction<T, U, R> operation, U input);

  /**
   * Starts an operation on the Nexus service with the provided input.
   *
   * @param operation The operation method to start, represented as a BiFunction.
   * @param input The input to the operation.
   * @param options for start operations
   */
  <U, R> NexusClientHandle<R> start(
      BiFunction<T, U, R> operation, U input, NexusOperationOptions options);

  // NOTE: These would also have async variations that return CompletableFutures
}
