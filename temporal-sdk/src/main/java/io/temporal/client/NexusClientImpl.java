package io.temporal.client;

import com.uber.m3.tally.Scope;
import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.Experimental;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.client.NamespaceInjectWorkflowServiceStubs;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

/**
 * Handle for interacting with a standalone Nexus operation execution.
 *
 * <p>Returned by {@link WorkflowClient} when starting a Nexus operation, and also constructable
 * from an existing operation ID for operating on an operation that was started elsewhere.
 */
@Experimental
public class NexusClientImpl implements NexusClient {

  private static final Logger log = LoggerFactory.getLogger(NexusClientImpl.class);

  private final WorkflowServiceStubs workflowServiceStubs;
  private final NexusClientOperationOptions options;
  private final GenericWorkflowClient genericClient;
  private final Scope metricsScope;
  private final NexusClientInterceptor nexusClientInterceptor;
  private final List<NexusClientInterceptor> interceptors;

  @Override
  public NexusClientHandle getHandle(String scheduleID) {
    return new NexusClientHandleImpl(nexusClientInterceptor);
  }


  public UntypedNexusClientHandle getHandle(
          String operationId,
          @Nullable String operationRunId) {
    return new UntypedNexusClientHandleImpl();
  }

//  /// Obtains typed handle to existing operations.
//  <R> NexusClientHandle<R> getHandle(
//          String operationId,
//          @Nullable String operationRunId,
//          Class<R> resultClass);
//
//  /// Obtains typed handle to existing operations.
//  /// For use with generic return types.
//  <R> NexusClientHandle<R> getHandle(
//          String operationId,
//          @Nullable String operationRunId,
//          Class<R> resultClass,
//          @Nullable Type resultType);

  public static NexusClient newInstance(
          WorkflowServiceStubs service, NexusClientOperationOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
            new NexusClientImpl(service, options), NexusClient.class);
  }

  NexusClientImpl(WorkflowServiceStubs workflowServiceStubs, NexusClientOperationOptions options) {
    //TODO - EVAN - do we need options.getInterceptors?
    workflowServiceStubs =
            new NamespaceInjectWorkflowServiceStubs(workflowServiceStubs, options.getNamespace());
    this.workflowServiceStubs = workflowServiceStubs;
    this.options = options;
    this.metricsScope =
            workflowServiceStubs
                    .getOptions()
                    .getMetricsScope()
                    .tagged(MetricsTag.defaultTags(options.getNamespace()));
    this.genericClient = new GenericWorkflowClientImpl(workflowServiceStubs, metricsScope);
    this.interceptors = options.getInterceptors();
    nexusClientInterceptor = initializeClientInvoker();
  }











  private NexusClientInterceptor initializeClientInvoker() {
    NexusClientInterceptorBase nexusClientInterceptor =
            new NexusClientInterceptorBase(genericClient, options);
    for (NexusClientInterceptor clientInterceptor : interceptors) {
      nexusClientInterceptor =
              clientInterceptor.nexusClientInterceptor(nexusClientInterceptor);
    }
    return nexusClientInterceptor;
  }

  @Override
  public StartNexusOperationExecutionResponse startNexusOperationExecution(@NonNull StartNexusOperationExecutionRequest request) {
    return null;
  }

  @Override
  public DescribeNexusOperationExecutionResponse describeNexusOperationExecution(@NonNull DescribeNexusOperationExecutionRequest request, @NonNull Deadline deadline) {
    return null;
  }

  @Override
  public CompletableFuture<DescribeNexusOperationExecutionResponse> describeNexusOperationExecutionAsync(@NonNull DescribeNexusOperationExecutionRequest request, @NonNull Deadline deadline) {
    return null;
  }

  @Override
  public PollNexusOperationExecutionResponse pollNexusOperationExecution(@NonNull PollNexusOperationExecutionRequest request, @NonNull Deadline deadline) {
    return null;
  }

  @Override
  public CompletableFuture<PollNexusOperationExecutionResponse> pollNexusOperationExecutionAsync(@NonNull PollNexusOperationExecutionRequest request, @NonNull Deadline deadline) {
    return null;
  }

  @Override
  public ListNexusOperationExecutionsResponse listNexusOperationExecutions(@NonNull ListNexusOperationExecutionsRequest request) {
    return null;
  }

  @Override
  public CountNexusOperationExecutionsResponse countNexusOperationExecutions(@NonNull CountNexusOperationExecutionsRequest request) {
    return null;
  }

  @Override
  public RequestCancelNexusOperationExecutionResponse requestCancelNexusOperationExecution(@NonNull RequestCancelNexusOperationExecutionRequest request) {
    return null;
  }

  @Override
  public TerminateNexusOperationExecutionResponse terminateNexusOperationExecution(@NonNull TerminateNexusOperationExecutionRequest request) {
    return null;
  }

  @Override
  public DeleteNexusOperationExecutionResponse deleteNexusOperationExecution(@NonNull DeleteNexusOperationExecutionRequest request) {
    return null;
  }
}
