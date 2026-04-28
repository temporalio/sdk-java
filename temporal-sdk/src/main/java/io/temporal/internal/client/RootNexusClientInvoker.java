package io.temporal.internal.client;

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
import io.temporal.client.NexusClientInterceptor;
import io.temporal.client.NexusClientOperationExecutionDescription;
import io.temporal.client.NexusClientOperationOptions;
import io.temporal.common.Experimental;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Root implementation of {@link NexusClientInterceptor} that converts the SDK's Java DTOs into
 * proto requests and delegates the actual gRPC calls to {@link GenericWorkflowClient}.
 */
@Experimental
public class RootNexusClientInvoker implements NexusClientInterceptor {

  private final GenericWorkflowClient genericClient;
  private final NexusClientOperationOptions clientOptions;

  public RootNexusClientInvoker(
      GenericWorkflowClient genericClient, NexusClientOperationOptions clientOptions) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
  }

  @Override
  public StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input) {
    StartNexusOperationExecutionRequest.Builder request =
        StartNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(UUID.randomUUID().toString())
            .setOperationId(input.getOperationId())
            .setEndpoint(input.getEndpoint())
            .setService(input.getService())
            .setOperation(input.getOperation())
            .putAllNexusHeader(input.getNexusHeader());

    input
        .getScheduleToCloseTimeout()
        .ifPresent(d -> request.setScheduleToCloseTimeout(ProtobufTimeUtils.toProtoDuration(d)));
    input.getInput().ifPresent(request::setInput);
    input.getSearchAttributes().ifPresent(request::setSearchAttributes);

    StartNexusOperationExecutionResponse response =
        genericClient.startNexusOperationExecution(request.build());
    return new StartNexusOperationExecutionOutput(response.getRunId(), response.getStarted());
  }

  @Override
  public DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input) {
    DescribeNexusOperationExecutionRequest request = buildDescribeRequest(input);
    DescribeNexusOperationExecutionResponse response =
        genericClient.describeNexusOperationExecution(request, input.getDeadline());
    return new DescribeNexusOperationExecutionOutput(
        new NexusClientOperationExecutionDescription(response));
  }

  @Override
  public CompletableFuture<DescribeNexusOperationExecutionOutput>
      describeNexusOperationExecutionAsync(DescribeNexusOperationExecutionInput input) {
    // GenericWorkflowClient does not expose an async describe variant today.
    // Run the blocking call on the common pool so the public async surface still works.
    return CompletableFuture.supplyAsync(() -> describeNexusOperationExecution(input));
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
    PollNexusOperationExecutionResponse response =
        genericClient.pollNexusOperationExecution(buildPollRequest(input), input.getDeadline());
    return toPollOutput(response);
  }

  @Override
  public CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input) {
    return genericClient
        .pollNexusOperationExecutionAsync(buildPollRequest(input), input.getDeadline())
        .thenApply(this::toPollOutput);
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
            .setRequestId(UUID.randomUUID().toString())
            .setOperationId(input.getOperationId());
    input.getRunId().ifPresent(request::setRunId);
    input.getReason().ifPresent(request::setReason);
    genericClient.requestCancelNexusOperationExecution(request.build());
  }

  @Override
  public void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input) {
    TerminateNexusOperationExecutionRequest.Builder request =
        TerminateNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(UUID.randomUUID().toString())
            .setOperationId(input.getOperationId());
    input.getRunId().ifPresent(request::setRunId);
    input.getReason().ifPresent(request::setReason);
    genericClient.terminateNexusOperationExecution(request.build());
  }

  @Override
  public void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input) {
    DeleteNexusOperationExecutionRequest.Builder request =
        DeleteNexusOperationExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setOperationId(input.getOperationId());
    input.getRunId().ifPresent(request::setRunId);
    genericClient.deleteNexusOperationExecution(request.build());
  }
}
