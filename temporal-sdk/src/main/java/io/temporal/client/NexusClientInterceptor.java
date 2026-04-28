package io.temporal.client;


import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public interface NexusClientInterceptor {

    StartNexusOperationExecutionResponse startNexusOperationExecution(
            @Nonnull StartNexusOperationExecutionRequest request);

    DescribeNexusOperationExecutionResponse describeNexusOperationExecution(
            @Nonnull DescribeNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

    CompletableFuture<DescribeNexusOperationExecutionResponse> describeNexusOperationExecutionAsync(
            @Nonnull DescribeNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

    PollNexusOperationExecutionResponse pollNexusOperationExecution(
            @Nonnull PollNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

    CompletableFuture<PollNexusOperationExecutionResponse> pollNexusOperationExecutionAsync(
            @Nonnull PollNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

    ListNexusOperationExecutionsResponse listNexusOperationExecutions(
            @Nonnull ListNexusOperationExecutionsRequest request);

    CountNexusOperationExecutionsResponse countNexusOperationExecutions(
            @Nonnull CountNexusOperationExecutionsRequest request);

    RequestCancelNexusOperationExecutionResponse requestCancelNexusOperationExecution(
            @Nonnull RequestCancelNexusOperationExecutionRequest request);

    TerminateNexusOperationExecutionResponse terminateNexusOperationExecution(
            @Nonnull TerminateNexusOperationExecutionRequest request);

    DeleteNexusOperationExecutionResponse deleteNexusOperationExecution(
            @Nonnull DeleteNexusOperationExecutionRequest request);



}
