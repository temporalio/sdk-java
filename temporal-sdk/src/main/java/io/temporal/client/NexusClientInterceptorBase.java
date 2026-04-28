package io.temporal.client;


import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.CompletableFuture;


//TODO - EVAN -
// Make input and output types so that we aren't exposing the protobuf types
//make the request and returns final, defined inside this class
// Anything not set by this needs to be exposed
//  -- also hide the polling token in describe
public class NexusClientInterceptorBase implements NexusClientInterceptor {
    private NexusClientInterceptor next;
    private final NexusClientOperationOptions options;

    public NexusClientInterceptorBase(NexusClientInterceptor next) {
        this.next = next;
    }

    @Override
    public StartNexusOperationExecutionResponse startNexusOperationExecution(@NonNull StartNexusOperationExecutionRequest request) {
        return next.startNexusOperationExecution(request);
    }

    @Override
    public DescribeNexusOperationExecutionResponse describeNexusOperationExecution(@NonNull DescribeNexusOperationExecutionRequest request,
                                                                                   @NonNull Deadline deadline) {
        return next.describeNexusOperationExecution(request, deadline);
    }

    @Override
    public CompletableFuture<DescribeNexusOperationExecutionResponse> describeNexusOperationExecutionAsync(
            @NonNull DescribeNexusOperationExecutionRequest request, @NonNull Deadline deadline) {
        return next.describeNexusOperationExecutionAsync(request, deadline);
    }

    @Override
    public PollNexusOperationExecutionResponse pollNexusOperationExecution(@NonNull PollNexusOperationExecutionRequest request,
                                                                           @NonNull Deadline deadline) {
        return next.pollNexusOperationExecution(request, deadline);
    }

    @Override
    public CompletableFuture<PollNexusOperationExecutionResponse> pollNexusOperationExecutionAsync(
            @NonNull PollNexusOperationExecutionRequest request, @NonNull Deadline deadline) {
        return next.pollNexusOperationExecutionAsync(request, deadline);
    }

    @Override
    public ListNexusOperationExecutionsResponse listNexusOperationExecutions(@NonNull ListNexusOperationExecutionsRequest request) {
        return next.listNexusOperationExecutions(request);
    }

    @Override
    public CountNexusOperationExecutionsResponse countNexusOperationExecutions(@NonNull CountNexusOperationExecutionsRequest request) {
        return next.countNexusOperationExecutions(request);
    }

    @Override
    public RequestCancelNexusOperationExecutionResponse requestCancelNexusOperationExecution(@NonNull RequestCancelNexusOperationExecutionRequest request) {
        return next.requestCancelNexusOperationExecution(request);
    }

    @Override
    public TerminateNexusOperationExecutionResponse terminateNexusOperationExecution(@NonNull TerminateNexusOperationExecutionRequest request) {
        return next.terminateNexusOperationExecution(request);
    }

    @Override
    public DeleteNexusOperationExecutionResponse deleteNexusOperationExecution(@NonNull DeleteNexusOperationExecutionRequest request) {
        return next.deleteNexusOperationExecution(request);
    }
}
