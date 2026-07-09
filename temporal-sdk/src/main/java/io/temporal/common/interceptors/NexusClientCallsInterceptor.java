package io.temporal.common.interceptors;

import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationExecutionMetadata;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-call interceptor for {@link NexusClient} and {@link NexusOperationHandle} operations on
 * standalone Nexus operation executions.
 *
 * <p>Implementations are produced by {@link
 * NexusClientInterceptor#nexusClientCallsInterceptor(NexusClientCallsInterceptor)} during {@link
 * NexusClient} construction. Prefer extending {@link NexusClientCallsInterceptorBase} and
 * overriding only the methods you need.
 */
@Experimental
public interface NexusClientCallsInterceptor {

  /**
   * Starts a standalone Nexus operation. The endpoint, service, operation name, input, and
   * scheduling options are carried in {@code input}.
   *
   * @param input endpoint, service name, operation name, encoded input, and start options
   * @return output containing the operation ID, server-assigned run ID, and whether the operation
   *     was started by this call (vs. de-duplicated to an existing one)
   */
  StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input);

  /**
   * Returns a point-in-time snapshot of a standalone Nexus operation execution.
   *
   * @param input operation ID and optional run ID
   * @return output wrapping the {@link NexusOperationExecutionDescription}
   */
  DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input);

  /**
   * Synchronously waits for a standalone Nexus operation to complete and returns the deserialized
   * result. Implementations own the poll loop, deadline enforcement, and {@link Payload} → {@code
   * R} deserialization. Blocks the calling thread for the duration.
   *
   * <p>If you implement this method, {@link #getNexusOperationResultAsync} most likely needs to be
   * implemented too.
   *
   * @param input operation ID, optional run ID, deadline, and the expected result class and type
   * @param <R> the expected result type
   * @return output wrapping the deserialized result
   * @throws NexusOperationFailedException if the operation completed with a failure
   * @throws TimeoutException if the deadline expires before the operation completes
   * @see #getNexusOperationResultAsync
   */
  <R> GetNexusOperationResultOutput<R> getNexusOperationResult(
      GetNexusOperationResultInput<R> input) throws TimeoutException;

  /**
   * Asynchronous variant of {@link #getNexusOperationResult} that returns a future without blocking
   * the calling thread.
   *
   * <p>If you implement this method, {@link #getNexusOperationResult} most likely needs to be
   * implemented too.
   *
   * @param input operation ID, optional run ID, deadline, and the expected result class and type
   * @param <R> the expected result type
   * @return a future that completes with the deserialized result, or completes exceptionally with
   *     {@link NexusOperationFailedException} on failure or {@link TimeoutException} on deadline
   *     expiry
   * @see #getNexusOperationResult
   */
  <R> CompletableFuture<GetNexusOperationResultOutput<R>> getNexusOperationResultAsync(
      GetNexusOperationResultInput<R> input);

  /**
   * Lists standalone Nexus operation executions matching a Visibility query. The returned output
   * contains a lazy {@link Stream} of deserialized {@link NexusOperationExecutionMetadata} objects;
   * pages are fetched on demand as the stream is consumed.
   *
   * @param input Visibility query string
   * @return output wrapping a lazy stream of matching operations
   */
  ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input);

  /**
   * Returns the count of standalone Nexus operation executions matching a Visibility query,
   * optionally grouped by attribute.
   *
   * @param input Visibility query string
   * @return output wrapping the total count and any aggregation groups
   */
  CountNexusOperationExecutionsOutput countNexusOperationExecutions(
      CountNexusOperationExecutionsInput input);

  /**
   * Requests cancellation of a running standalone Nexus operation. The server forwards the cancel
   * request to the operation handler, which may honour or ignore it.
   *
   * @param input operation ID, optional run ID, and optional human-readable cancellation reason
   * @return an empty output that exists so the call can carry fields in the future
   */
  RequestCancelNexusOperationExecutionOutput requestCancelNexusOperationExecution(
      RequestCancelNexusOperationExecutionInput input);

  /**
   * Forcefully terminates a standalone Nexus operation. Unlike cancellation, termination is
   * immediate and cannot be intercepted by the operation handler.
   *
   * @param input operation ID, optional run ID, and optional human-readable termination reason
   * @return an empty output that exists so the call can carry fields in the future
   */
  TerminateNexusOperationExecutionOutput terminateNexusOperationExecution(
      TerminateNexusOperationExecutionInput input);

  /**
   * Deletes a closed standalone Nexus operation execution from the server's visibility store. The
   * operation must already be in a terminal state.
   *
   * @param input operation ID and optional run ID
   * @return an empty output that exists so the call can carry fields in the future
   */
  DeleteNexusOperationExecutionOutput deleteNexusOperationExecution(
      DeleteNexusOperationExecutionInput input);

  final class StartNexusOperationExecutionInput {
    private final String endpoint;
    private final String service;
    private final String operation;
    private final @Nullable Payload input;
    private final StartNexusOperationOptions options;
    private final Map<String, String> headers;

    public StartNexusOperationExecutionInput(
        String endpoint,
        String service,
        String operation,
        @Nullable Payload input,
        StartNexusOperationOptions options,
        Map<String, String> headers) {
      this.endpoint = endpoint;
      this.service = service;
      this.operation = operation;
      this.input = input;
      this.options = options;
      this.headers = headers == null ? Collections.emptyMap() : headers;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public String getService() {
      return service;
    }

    public String getOperation() {
      return operation;
    }

    public Optional<Payload> getInput() {
      return Optional.ofNullable(input);
    }

    public StartNexusOperationOptions getOptions() {
      return options;
    }

    /**
     * Nexus protocol headers to forward to the handler. Interceptors implementing context
     * propagation (tracing, baggage, etc.) populate this map by wrapping the call chain.
     */
    public Map<String, String> getHeaders() {
      return headers;
    }
  }

  final class StartNexusOperationExecutionOutput {
    private final String operationId;
    private final String runId;
    private final boolean started;

    public StartNexusOperationExecutionOutput(String operationId, String runId, boolean started) {
      this.operationId = operationId;
      this.runId = runId;
      this.started = started;
    }

    public String getOperationId() {
      return operationId;
    }

    public String getRunId() {
      return runId;
    }

    public boolean isStarted() {
      return started;
    }
  }

  final class DescribeNexusOperationExecutionInput {
    private final String operationId;
    private final @Nullable String runId;

    public DescribeNexusOperationExecutionInput(String operationId, @Nullable String runId) {
      this.operationId = operationId;
      this.runId = runId;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }
  }

  final class DescribeNexusOperationExecutionOutput {
    private final NexusOperationExecutionDescription description;

    public DescribeNexusOperationExecutionOutput(NexusOperationExecutionDescription description) {
      this.description = description;
    }

    public NexusOperationExecutionDescription getDescription() {
      return description;
    }
  }

  final class GetNexusOperationResultInput<R> {
    private final String operationId;
    private final @Nullable String runId;
    private final @Nonnull Deadline deadline;
    private final Class<R> resultClass;
    private final @Nullable Type resultType;

    public GetNexusOperationResultInput(
        String operationId,
        @Nullable String runId,
        @Nonnull Deadline deadline,
        Class<R> resultClass,
        @Nullable Type resultType) {
      this.operationId = operationId;
      this.runId = runId;
      this.deadline = deadline;
      this.resultClass = resultClass;
      this.resultType = resultType;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }

    @Nonnull
    public Deadline getDeadline() {
      return deadline;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    @Nullable
    public Type getResultType() {
      return resultType;
    }
  }

  final class GetNexusOperationResultOutput<R> {
    private final R result;

    public GetNexusOperationResultOutput(R result) {
      this.result = result;
    }

    public R getResult() {
      return result;
    }
  }

  final class ListNexusOperationExecutionsInput {
    private final @Nullable String query;

    public ListNexusOperationExecutionsInput(@Nullable String query) {
      this.query = query;
    }

    public Optional<String> getQuery() {
      return Optional.ofNullable(query);
    }
  }

  /**
   * Result of a list call. Holds a lazy {@link Stream} of deserialized {@link
   * NexusOperationExecutionMetadata} objects; pages are fetched on demand as the stream is
   * consumed. A {@code Stream} is single-use and must not be consumed more than once.
   */
  final class ListNexusOperationExecutionsOutput {
    private final Stream<NexusOperationExecutionMetadata> operations;

    public ListNexusOperationExecutionsOutput(Stream<NexusOperationExecutionMetadata> operations) {
      this.operations = operations;
    }

    public Stream<NexusOperationExecutionMetadata> getOperations() {
      return operations;
    }
  }

  final class CountNexusOperationExecutionsInput {
    private final @Nullable String query;

    public CountNexusOperationExecutionsInput(@Nullable String query) {
      this.query = query;
    }

    public Optional<String> getQuery() {
      return Optional.ofNullable(query);
    }
  }

  final class CountNexusOperationExecutionsOutput {
    private final NexusOperationExecutionCount count;

    public CountNexusOperationExecutionsOutput(NexusOperationExecutionCount count) {
      this.count = count;
    }

    public NexusOperationExecutionCount getCount() {
      return count;
    }
  }

  final class RequestCancelNexusOperationExecutionInput {
    private final String operationId;
    private final @Nullable String runId;
    private final @Nullable String reason;

    public RequestCancelNexusOperationExecutionInput(
        String operationId, @Nullable String runId, @Nullable String reason) {
      this.operationId = operationId;
      this.runId = runId;
      this.reason = reason;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }

    public Optional<String> getReason() {
      return Optional.ofNullable(reason);
    }
  }

  final class RequestCancelNexusOperationExecutionOutput {
    public RequestCancelNexusOperationExecutionOutput() {
      // This output is intentionally empty and exists so it can carry fields in the future.
    }
  }

  final class TerminateNexusOperationExecutionInput {
    private final String operationId;
    private final @Nullable String runId;
    private final @Nullable String reason;

    public TerminateNexusOperationExecutionInput(
        String operationId, @Nullable String runId, @Nullable String reason) {
      this.operationId = operationId;
      this.runId = runId;
      this.reason = reason;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }

    public Optional<String> getReason() {
      return Optional.ofNullable(reason);
    }
  }

  final class TerminateNexusOperationExecutionOutput {
    public TerminateNexusOperationExecutionOutput() {
      // This output is intentionally empty and exists so it can carry fields in the future.
    }
  }

  final class DeleteNexusOperationExecutionInput {
    private final String operationId;
    private final @Nullable String runId;

    public DeleteNexusOperationExecutionInput(String operationId, @Nullable String runId) {
      this.operationId = operationId;
      this.runId = runId;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }
  }

  final class DeleteNexusOperationExecutionOutput {
    public DeleteNexusOperationExecutionOutput() {
      // This output is intentionally empty and exists so it can carry fields in the future.
    }
  }
}
