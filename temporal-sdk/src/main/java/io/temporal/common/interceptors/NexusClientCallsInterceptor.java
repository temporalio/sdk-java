package io.temporal.common.interceptors;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.nexus.v1.NexusOperationExecutionListInfo;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientHandle;
import io.temporal.client.NexusClientOperationExecutionDescription;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-call interceptor for {@link NexusClient} and {@link NexusClientHandle} operations on
 * standalone Nexus operation executions.
 *
 * <p>Implementations are produced by {@link
 * NexusClientInterceptor#nexusClientCallsInterceptor(NexusClientCallsInterceptor)} during {@link
 * NexusClient} construction. Prefer extending {@link NexusClientCallsInterceptorBase} and
 * overriding only the methods you need.
 */
@Experimental
public interface NexusClientCallsInterceptor {

  StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input);

  DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input);

  CompletableFuture<DescribeNexusOperationExecutionOutput> describeNexusOperationExecutionAsync(
      DescribeNexusOperationExecutionInput input);

  PollNexusOperationExecutionOutput pollNexusOperationExecution(
      PollNexusOperationExecutionInput input);

  CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input);

  ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input);

  CountNexusOperationExecutionsOutput countNexusOperationExecutions(
      CountNexusOperationExecutionsInput input);

  void requestCancelNexusOperationExecution(RequestCancelNexusOperationExecutionInput input);

  void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input);

  void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input);

  final class StartNexusOperationExecutionInput {
    private final String endpoint;
    private final String service;
    private final String operation;
    private final @Nullable Payload input;
    private final StartNexusOperationOptions options;

    public StartNexusOperationExecutionInput(
        String endpoint,
        String service,
        String operation,
        @Nullable Payload input,
        StartNexusOperationOptions options) {
      this.endpoint = endpoint;
      this.service = service;
      this.operation = operation;
      this.input = input;
      this.options = options;
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
    private final @Nonnull Deadline deadline;

    public DescribeNexusOperationExecutionInput(
        String operationId,
        @Nullable String runId,
        boolean includeInput,
        boolean includeOutcome,
        @Nonnull Deadline deadline) {
      this.operationId = operationId;
      this.runId = runId;
      this.includeInput = includeInput;
      this.includeOutcome = includeOutcome;
      this.deadline = deadline;
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

    public Deadline getDeadline() {
      return deadline;
    }
  }

  final class DescribeNexusOperationExecutionOutput {
    private final NexusClientOperationExecutionDescription description;

    public DescribeNexusOperationExecutionOutput(
        NexusClientOperationExecutionDescription description) {
      this.description = description;
    }

    public NexusClientOperationExecutionDescription getDescription() {
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
