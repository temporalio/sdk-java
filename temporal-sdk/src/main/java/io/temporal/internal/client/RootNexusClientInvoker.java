package io.temporal.internal.client;

import com.google.common.base.Strings;
import com.google.common.collect.Iterators;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.errordetails.v1.NexusOperationExecutionAlreadyStartedFailure;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.workflowservice.v1.CountNexusOperationExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountNexusOperationExecutionsResponse;
import io.temporal.api.workflowservice.v1.DeleteNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeNexusOperationExecutionResponse;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionResponse;
import io.temporal.api.workflowservice.v1.RequestCancelNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.StartNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.StartNexusOperationExecutionResponse;
import io.temporal.api.workflowservice.v1.TerminateNexusOperationExecutionRequest;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationAlreadyStartedException;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationExecutionMetadata;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.NexusOperationNotFoundException;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.serviceclient.StatusUtils;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 * Root implementation of {@link NexusClientCallsInterceptor} that converts the SDK's Java DTOs into
 * proto requests and delegates the actual gRPC calls to {@link GenericWorkflowClient}.
 */
@Experimental
public class RootNexusClientInvoker implements NexusClientCallsInterceptor {

  private final GenericWorkflowClient genericClient;
  private final NexusClientOptions clientOptions;

  public RootNexusClientInvoker(
      GenericWorkflowClient genericClient, NexusClientOptions clientOptions) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
  }

  @Override
  public StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input) {
    StartNexusOperationOptions options = input.getOptions();
    // The builder validates that id is non-null; this is a defense-in-depth assertion.
    String operationId = Objects.requireNonNull(options.getId(), "StartNexusOperationOptions.id");
    StartNexusOperationExecutionRequest.Builder request =
        StartNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setRequestId(UUID.randomUUID().toString())
            .setOperationId(operationId)
            .setEndpoint(input.getEndpoint())
            .setService(input.getService())
            .setOperation(input.getOperation());
    // Ensure that the headers are lowercase.
    input.getHeaders().forEach((k, v) -> request.putNexusHeader(k.toLowerCase(), v));

    if (options.getScheduleToCloseTimeout() != null) {
      request.setScheduleToCloseTimeout(
          ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()));
    }
    if (options.getScheduleToStartTimeout() != null) {
      request.setScheduleToStartTimeout(
          ProtobufTimeUtils.toProtoDuration(options.getScheduleToStartTimeout()));
    }
    if (options.getStartToCloseTimeout() != null) {
      request.setStartToCloseTimeout(
          ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()));
    }
    input.getInput().ifPresent(request::setInput);
    if (options.getTypedSearchAttributes() != null) {
      request.setSearchAttributes(
          io.temporal.internal.common.SearchAttributesUtil.encodeTyped(
              options.getTypedSearchAttributes()));
    }
    if (options.getIdReusePolicy() != null) {
      request.setIdReusePolicy(options.getIdReusePolicy());
    }
    if (options.getIdConflictPolicy() != null) {
      request.setIdConflictPolicy(options.getIdConflictPolicy());
    }
    if (options.getSummary() != null) {
      UserMetadata metadata =
          WorkflowExecutionUtils.makeUserMetaData(
              options.getSummary(), null, clientOptions.getDataConverter());
      if (metadata != null) {
        request.setUserMetadata(metadata);
      }
    }

    StartNexusOperationExecutionResponse response;
    try {
      response = genericClient.startNexusOperationExecution(request.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
        NexusOperationExecutionAlreadyStartedFailure detail =
            StatusUtils.getFailure(e, NexusOperationExecutionAlreadyStartedFailure.class);
        if (detail != null) {
          String runId = Strings.emptyToNull(detail.getRunId());
          throw new NexusOperationAlreadyStartedException(
              operationId, input.getOperation(), runId, e);
        }
      }
      throw e;
    }
    return new StartNexusOperationExecutionOutput(
        operationId, response.getRunId(), response.getStarted());
  }

  @Override
  public DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input) {
    DescribeNexusOperationExecutionRequest request = buildDescribeRequest(input);
    DescribeNexusOperationExecutionResponse response;
    try {
      response = genericClient.describeNexusOperationExecution(request);
    } catch (StatusRuntimeException e) {
      throw mapNotFound(input.getOperationId(), input.getRunId().orElse(null), e);
    }
    return new DescribeNexusOperationExecutionOutput(
        new NexusOperationExecutionDescription(
            response, clientOptions.getDataConverter(), clientOptions.getNamespace()));
  }

  private DescribeNexusOperationExecutionRequest buildDescribeRequest(
      DescribeNexusOperationExecutionInput input) {
    // Describe defaults: outcome is included so callers can read the success/failure of completed
    // operations; input is omitted to keep responses small. These are SDK-internal decisions and
    // not exposed through the interceptor surface.
    DescribeNexusOperationExecutionRequest.Builder request =
        DescribeNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setOperationId(input.getOperationId())
            .setIncludeInput(false)
            .setIncludeOutcome(true);
    input.getRunId().ifPresent(request::setRunId);
    return request.build();
  }

  @Override
  public <R> GetNexusOperationResultOutput<R> getNexusOperationResult(
      GetNexusOperationResultInput<R> input) throws TimeoutException {
    String operationId = input.getOperationId();
    String runId = input.getRunId().orElse(null);
    while (true) {
      PollNexusOperationExecutionResponse response;
      try {
        response =
            genericClient.pollNexusOperationExecution(buildPollRequest(input), input.getDeadline());
      } catch (StatusRuntimeException e) {
        if (input.getDeadline().isExpired()
            && Status.Code.DEADLINE_EXCEEDED.equals(e.getStatus().getCode())) {
          throw new TimeoutException("getResult timed out before the operation completed");
        }
        throw mapNotFound(operationId, runId, e);
      }
      if (response.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
        return extractResult(operationId, runId, response, input);
      }
    }
  }

  @Override
  public <R> CompletableFuture<GetNexusOperationResultOutput<R>> getNexusOperationResultAsync(
      GetNexusOperationResultInput<R> input) {
    return pollAsyncUntilClosed(input)
        .thenApply(
            response ->
                extractResult(
                    input.getOperationId(), input.getRunId().orElse(null), response, input));
  }

  private CompletableFuture<PollNexusOperationExecutionResponse> pollAsyncUntilClosed(
      GetNexusOperationResultInput<?> input) {
    String operationId = input.getOperationId();
    String runId = input.getRunId().orElse(null);
    return genericClient
        .pollNexusOperationExecutionAsync(buildPollRequest(input), input.getDeadline())
        .handle(
            (response, err) -> {
              if (err == null) {
                if (response.getWaitStage()
                    == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
                  return CompletableFuture.completedFuture(response);
                }
                return pollAsyncUntilClosed(input);
              }
              CompletableFuture<PollNexusOperationExecutionResponse> failed =
                  new CompletableFuture<>();
              Throwable cause = err instanceof CompletionException ? err.getCause() : err;
              if (input.getDeadline().isExpired()
                  && cause instanceof StatusRuntimeException
                  && Status.Code.DEADLINE_EXCEEDED.equals(
                      ((StatusRuntimeException) cause).getStatus().getCode())) {
                failed.completeExceptionally(
                    new TimeoutException("getResult timed out before the operation completed"));
              } else if (cause instanceof StatusRuntimeException) {
                failed.completeExceptionally(
                    mapNotFound(operationId, runId, (StatusRuntimeException) cause));
              } else {
                failed.completeExceptionally(err);
              }
              return failed;
            })
        .thenCompose(f -> f);
  }

  private PollNexusOperationExecutionRequest buildPollRequest(
      GetNexusOperationResultInput<?> input) {
    PollNexusOperationExecutionRequest.Builder request =
        PollNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setOperationId(input.getOperationId())
            // Poll always waits for the operation to reach a terminal state; intermediate stages
            // are not exposed through the interceptor surface.
            .setWaitStage(NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED);
    input.getRunId().ifPresent(request::setRunId);
    return request.build();
  }

  private <R> GetNexusOperationResultOutput<R> extractResult(
      String operationId,
      @Nullable String runId,
      PollNexusOperationExecutionResponse response,
      GetNexusOperationResultInput<R> input) {
    if (response.hasFailure()) {
      Failure failure = response.getFailure();
      throw new NexusOperationFailedException(
          "Nexus operation failed: operationId='" + operationId + "'",
          operationId,
          runId,
          clientOptions.getDataConverter().failureToException(failure));
    }
    if (!response.hasResult()) {
      throw new NexusOperationFailedException(
          "Nexus operation '"
              + operationId
              + "' is closed but the poll response carried neither a result nor a failure",
          operationId,
          runId,
          new IllegalStateException(
              "malformed PollNexusOperationExecutionResponse: outcome oneof is not set"));
    }
    Payload payload = response.getResult();
    R deserialized =
        clientOptions
            .getDataConverter()
            .fromPayload(
                payload,
                input.getResultClass(),
                input.getResultType() != null ? input.getResultType() : input.getResultClass());
    return new GetNexusOperationResultOutput<>(deserialized);
  }

  @Override
  public ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input) {

    ListNexusOperationExecutionIterator iterator =
        new ListNexusOperationExecutionIterator(
            input.getQuery().orElse(null), clientOptions.getNamespace(), genericClient);
    iterator.init();
    Iterator<NexusOperationExecutionMetadata> wrappedIterator =
        Iterators.transform(iterator, NexusOperationExecutionMetadata::fromListInfo);

    final int characteristics = Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    return new ListNexusOperationExecutionsOutput(
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(wrappedIterator, characteristics), false));
  }

  @Override
  public CountNexusOperationExecutionsOutput countNexusOperationExecutions(
      CountNexusOperationExecutionsInput input) {
    CountNexusOperationExecutionsRequest.Builder request =
        CountNexusOperationExecutionsRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace());
    input.getQuery().ifPresent(request::setQuery);

    CountNexusOperationExecutionsResponse response =
        genericClient.countNexusOperationExecutions(request.build());

    java.util.List<NexusOperationExecutionCount.AggregationGroup> groups =
        new java.util.ArrayList<>(response.getGroupsCount());
    for (CountNexusOperationExecutionsResponse.AggregationGroup g : response.getGroupsList()) {
      groups.add(
          new NexusOperationExecutionCount.AggregationGroup(g.getCount(), g.getGroupValuesList()));
    }
    return new CountNexusOperationExecutionsOutput(
        new NexusOperationExecutionCount(response.getCount(), groups));
  }

  @Override
  public RequestCancelNexusOperationExecutionOutput requestCancelNexusOperationExecution(
      RequestCancelNexusOperationExecutionInput input) {
    RequestCancelNexusOperationExecutionRequest.Builder request =
        RequestCancelNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setRequestId(UUID.randomUUID().toString())
            .setOperationId(input.getOperationId());
    input.getRunId().ifPresent(request::setRunId);
    input.getReason().ifPresent(request::setReason);
    try {
      genericClient.requestCancelNexusOperationExecution(request.build());
    } catch (StatusRuntimeException e) {
      throw mapNotFound(input.getOperationId(), input.getRunId().orElse(null), e);
    }
    return new RequestCancelNexusOperationExecutionOutput();
  }

  @Override
  public TerminateNexusOperationExecutionOutput terminateNexusOperationExecution(
      TerminateNexusOperationExecutionInput input) {
    TerminateNexusOperationExecutionRequest.Builder request =
        TerminateNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setRequestId(UUID.randomUUID().toString())
            .setOperationId(input.getOperationId());
    input.getRunId().ifPresent(request::setRunId);
    input.getReason().ifPresent(request::setReason);
    try {
      genericClient.terminateNexusOperationExecution(request.build());
    } catch (StatusRuntimeException e) {
      throw mapNotFound(input.getOperationId(), input.getRunId().orElse(null), e);
    }
    return new TerminateNexusOperationExecutionOutput();
  }

  @Override
  public DeleteNexusOperationExecutionOutput deleteNexusOperationExecution(
      DeleteNexusOperationExecutionInput input) {
    DeleteNexusOperationExecutionRequest.Builder request =
        DeleteNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setOperationId(input.getOperationId());
    input.getRunId().ifPresent(request::setRunId);
    try {
      genericClient.deleteNexusOperationExecution(request.build());
    } catch (StatusRuntimeException e) {
      throw mapNotFound(input.getOperationId(), input.getRunId().orElse(null), e);
    }
    return new DeleteNexusOperationExecutionOutput();
  }

  /**
   * Maps a {@link StatusRuntimeException} with {@code NOT_FOUND} status to a typed {@link
   * NexusOperationNotFoundException}; otherwise returns the original exception unchanged so the
   * caller can rethrow.
   */
  private static RuntimeException mapNotFound(
      String operationId, @Nullable String runId, StatusRuntimeException e) {
    if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
      return new NexusOperationNotFoundException(operationId, runId, e);
    }
    return e;
  }
}
