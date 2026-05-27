package io.temporal.common.interceptors;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.nexus.v1.NexusOperationExecutionListInfo;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationHandle;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
   * @param input operation ID, optional run ID, and flags controlling whether to include input and
   *     outcome payloads
   * @return output wrapping the {@link NexusOperationExecutionDescription}
   */
  DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input);

  /**
   * Synchronously long-polls the server until the Nexus operation reaches the wait stage requested
   * in {@code input}, then returns the outcome. Blocks the calling thread for the duration.
   *
   * @param input operation ID, optional run ID, target wait stage, and the deadline bounding the
   *     poll
   * @return output containing the run ID, wait stage reached, operation token, and either the
   *     result payload or failure (when the operation has reached a terminal stage)
   */
  PollNexusOperationExecutionOutput pollNexusOperationExecution(
      PollNexusOperationExecutionInput input);

  /**
   * Asynchronous variant of {@link #pollNexusOperationExecution} that returns a future without
   * blocking the calling thread.
   *
   * @param input operation ID, optional run ID, target wait stage, and the deadline bounding the
   *     poll
   * @return a future that completes with the poll output, or completes exceptionally if the poll
   *     fails or the deadline expires
   */
  CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input);

  /**
   * Lists standalone Nexus operation executions matching a Visibility query, with paging support.
   *
   * @param input Visibility query string, page size, and optional next-page token from a prior call
   * @return output wrapping the matching operations and the next-page token (empty when the result
   *     set is exhausted)
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
   */
  void requestCancelNexusOperationExecution(RequestCancelNexusOperationExecutionInput input);

  /**
   * Forcefully terminates a standalone Nexus operation. Unlike cancellation, termination is
   * immediate and cannot be intercepted by the operation handler.
   *
   * @param input operation ID, optional run ID, and optional human-readable termination reason
   */
  void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input);

  /**
   * Deletes a closed standalone Nexus operation execution from the server's visibility store. The
   * operation must already be in a terminal state.
   *
   * @param input operation ID and optional run ID
   */
  void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input);

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
    private final boolean includeInput;
    private final boolean includeOutcome;

    public DescribeNexusOperationExecutionInput(
        String operationId, @Nullable String runId, boolean includeInput, boolean includeOutcome) {
      this.operationId = operationId;
      this.runId = runId;
      this.includeInput = includeInput;
      this.includeOutcome = includeOutcome;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }

    public boolean isIncludeInput() {
      return includeInput;
    }

    public boolean isIncludeOutcome() {
      return includeOutcome;
    }
  }

  final class DescribeNexusOperationExecutionOutput {
    private final NexusOperationExecutionDescription description;

    public DescribeNexusOperationExecutionOutput(
        NexusOperationExecutionDescription description) {
      this.description = description;
    }

    public NexusOperationExecutionDescription getDescription() {
      return description;
    }
  }

  final class PollNexusOperationExecutionInput {
    private final String operationId;
    private final @Nullable String runId;
    private final NexusOperationWaitStage waitStage;
    private final @Nonnull Deadline deadline;

    public PollNexusOperationExecutionInput(
        String operationId,
        @Nullable String runId,
        NexusOperationWaitStage waitStage,
        @Nonnull Deadline deadline) {
      this.operationId = operationId;
      this.runId = runId;
      this.waitStage = waitStage;
      this.deadline = deadline;
    }

    public String getOperationId() {
      return operationId;
    }

    public Optional<String> getRunId() {
      return Optional.ofNullable(runId);
    }

    public NexusOperationWaitStage getWaitStage() {
      return waitStage;
    }

    public Deadline getDeadline() {
      return deadline;
    }
  }

  final class PollNexusOperationExecutionOutput {
    private final String runId;
    private final NexusOperationWaitStage waitStage;
    private final String operationToken;
    private final @Nullable Payload result;
    private final @Nullable Failure failure;

    public PollNexusOperationExecutionOutput(
        String runId,
        NexusOperationWaitStage waitStage,
        String operationToken,
        @Nullable Payload result,
        @Nullable Failure failure) {
      this.runId = runId;
      this.waitStage = waitStage;
      this.operationToken = operationToken;
      this.result = result;
      this.failure = failure;
    }

    public String getRunId() {
      return runId;
    }

    public NexusOperationWaitStage getWaitStage() {
      return waitStage;
    }

    public String getOperationToken() {
      return operationToken;
    }

    public Optional<Payload> getResult() {
      return Optional.ofNullable(result);
    }

    public Optional<Failure> getFailure() {
      return Optional.ofNullable(failure);
    }
  }

  final class ListNexusOperationExecutionsInput {
    private final @Nullable String query;
    private final int pageSize;
    private final @Nullable ByteString nextPageToken;

    public ListNexusOperationExecutionsInput(
        @Nullable String query, int pageSize, @Nullable ByteString nextPageToken) {
      this.query = query;
      this.pageSize = pageSize;
      this.nextPageToken = nextPageToken;
    }

    public Optional<String> getQuery() {
      return Optional.ofNullable(query);
    }

    public int getPageSize() {
      return pageSize;
    }

    public Optional<ByteString> getNextPageToken() {
      return Optional.ofNullable(nextPageToken);
    }
  }

  final class ListNexusOperationExecutionsOutput {
    private final List<NexusOperationExecutionListInfo> operations;
    private final ByteString nextPageToken;

    public ListNexusOperationExecutionsOutput(
        List<NexusOperationExecutionListInfo> operations, ByteString nextPageToken) {
      this.operations = Collections.unmodifiableList(operations);
      this.nextPageToken = nextPageToken;
    }

    public List<NexusOperationExecutionListInfo> getOperations() {
      return operations;
    }

    public ByteString getNextPageToken() {
      return nextPageToken;
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
    private final long count;
    private final List<AggregationGroup> groups;

    public CountNexusOperationExecutionsOutput(long count, List<AggregationGroup> groups) {
      this.count = count;
      this.groups = Collections.unmodifiableList(groups);
    }

    public long getCount() {
      return count;
    }

    public List<AggregationGroup> getGroups() {
      return groups;
    }

    public static final class AggregationGroup {
      private final List<Payload> groupValues;
      private final long count;

      public AggregationGroup(List<Payload> groupValues, long count) {
        this.groupValues = Collections.unmodifiableList(groupValues);
        this.count = count;
      }

      public List<Payload> getGroupValues() {
        return groupValues;
      }

      public long getCount() {
        return count;
      }
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
}
