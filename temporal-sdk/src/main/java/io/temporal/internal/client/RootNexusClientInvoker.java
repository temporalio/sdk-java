package io.temporal.internal.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.errordetails.v1.NexusOperationExecutionAlreadyStartedFailure;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.workflowservice.v1.CountNexusOperationExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountNexusOperationExecutionsResponse;
import io.temporal.api.workflowservice.v1.DeleteNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeNexusOperationExecutionResponse;
import io.temporal.api.workflowservice.v1.ListNexusOperationExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListNexusOperationExecutionsResponse;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionResponse;
import io.temporal.api.workflowservice.v1.RequestCancelNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.StartNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.StartNexusOperationExecutionResponse;
import io.temporal.api.workflowservice.v1.TerminateNexusOperationExecutionRequest;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationAlreadyStartedException;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationNotFoundException;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.serviceclient.StatusUtils;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    String operationId = options.getId() != null ? options.getId() : UUID.randomUUID().toString();
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
              options.getSummary(), /* details= */ null, clientOptions.getDataConverter());
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
          String runId = detail.getRunId().isEmpty() ? null : detail.getRunId();
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
    DescribeNexusOperationExecutionRequest.Builder request =
        DescribeNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setOperationId(input.getOperationId())
            .setIncludeInput(input.isIncludeInput())
            .setIncludeOutcome(input.isIncludeOutcome());
    input.getRunId().ifPresent(request::setRunId);
    return request.build();
  }

  @Override
  public PollNexusOperationExecutionOutput pollNexusOperationExecution(
      PollNexusOperationExecutionInput input) {
    PollNexusOperationExecutionResponse response;
    try {
      response =
          genericClient.pollNexusOperationExecution(buildPollRequest(input), input.getDeadline());
    } catch (StatusRuntimeException e) {
      throw mapNotFound(input.getOperationId(), input.getRunId().orElse(null), e);
    }
    return toPollOutput(response);
  }

  @Override
  public CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input) {
    String operationId = input.getOperationId();
    String runId = input.getRunId().orElse(null);
    return genericClient
        .pollNexusOperationExecutionAsync(buildPollRequest(input), input.getDeadline())
        .handle(
            (response, err) -> {
              if (err == null) {
                return CompletableFuture.completedFuture(toPollOutput(response));
              }
              Throwable cause = err instanceof CompletionException ? err.getCause() : err;
              CompletableFuture<PollNexusOperationExecutionOutput> failed =
                  new CompletableFuture<>();
              if (cause instanceof StatusRuntimeException) {
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
      PollNexusOperationExecutionInput input) {
    PollNexusOperationExecutionRequest.Builder request =
        PollNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setOperationId(input.getOperationId())
            .setWaitStage(input.getWaitStage());
    input.getRunId().ifPresent(request::setRunId);
    return request.build();
  }

  private PollNexusOperationExecutionOutput toPollOutput(
      PollNexusOperationExecutionResponse response) {
    return new PollNexusOperationExecutionOutput(
        response.getRunId(),
        response.getWaitStage(),
        response.getOperationToken(),
        response.hasResult() ? response.getResult() : null,
        response.hasFailure() ? response.getFailure() : null);
  }

  @Override
  public ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input) {
    ListNexusOperationExecutionsRequest.Builder request =
        ListNexusOperationExecutionsRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setPageSize(input.getPageSize());
    input.getQuery().ifPresent(request::setQuery);
    input.getNextPageToken().ifPresent(request::setNextPageToken);

    ListNexusOperationExecutionsResponse response =
        genericClient.listNexusOperationExecutions(request.build());
    return new ListNexusOperationExecutionsOutput(
        response.getOperationsList(), response.getNextPageToken());
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

    java.util.List<CountNexusOperationExecutionsOutput.AggregationGroup> groups =
        new java.util.ArrayList<>(response.getGroupsCount());
    for (CountNexusOperationExecutionsResponse.AggregationGroup g : response.getGroupsList()) {
      groups.add(
          new CountNexusOperationExecutionsOutput.AggregationGroup(
              g.getGroupValuesList(), g.getCount()));
    }
    return new CountNexusOperationExecutionsOutput(response.getCount(), groups);
  }

  @Override
  public void requestCancelNexusOperationExecution(
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
  }

  @Override
  public void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input) {
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
  }

  @Override
  public void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input) {
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
