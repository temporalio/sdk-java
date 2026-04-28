package io.temporal.client;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import com.uber.m3.tally.Scope;
import io.temporal.client.NexusClientInterceptor.CountNexusOperationExecutionsInput;
import io.temporal.client.NexusClientInterceptor.CountNexusOperationExecutionsOutput;
import io.temporal.client.NexusClientInterceptor.DeleteNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.DescribeNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.DescribeNexusOperationExecutionOutput;
import io.temporal.client.NexusClientInterceptor.ListNexusOperationExecutionsInput;
import io.temporal.client.NexusClientInterceptor.ListNexusOperationExecutionsOutput;
import io.temporal.client.NexusClientInterceptor.PollNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.PollNexusOperationExecutionOutput;
import io.temporal.client.NexusClientInterceptor.RequestCancelNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.StartNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.StartNexusOperationExecutionOutput;
import io.temporal.client.NexusClientInterceptor.TerminateNexusOperationExecutionInput;
import io.temporal.common.Experimental;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.client.NamespaceInjectWorkflowServiceStubs;
import io.temporal.internal.client.RootNexusClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Experimental
public class NexusClientImpl implements NexusClient {

  private static final Logger log = LoggerFactory.getLogger(NexusClientImpl.class);

  private final WorkflowServiceStubs workflowServiceStubs;
  private final NexusClientOperationOptions options;
  private final GenericWorkflowClient genericClient;
  private final Scope metricsScope;
  private final NexusClientInterceptor nexusClientInterceptor;
  private final List<NexusClientInterceptor> interceptors;

  public static NexusClient newInstance(
      WorkflowServiceStubs service, NexusClientOperationOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new NexusClientImpl(service, options), NexusClient.class);
  }

  NexusClientImpl(WorkflowServiceStubs workflowServiceStubs, NexusClientOperationOptions options) {
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
    this.nexusClientInterceptor = initializeClientInvoker();
  }

  private NexusClientInterceptor initializeClientInvoker() {
    NexusClientInterceptor invoker = new RootNexusClientInvoker(genericClient, options);
    // TODO: chain user-provided interceptors once a wrap factory is defined on
    // NexusClientInterceptor (mirror ScheduleClientInterceptor.scheduleClientCallsInterceptor).
    return invoker;
  }

  @Override
  public NexusClientHandle getHandle(String scheduleID) {
    return new NexusClientHandleImpl(nexusClientInterceptor);
  }

  @Override
  public UntypedNexusClientHandle getHandle(String operationId, @Nullable String operationRunId) {
    return new UntypedNexusClientHandleImpl();
  }

  @Override
  public <R> NexusClientHandle<R> getHandle(
      String operationId, @Nullable String operationRunId, Class<R> resultClass) {
    return null;
  }

  @Override
  public <R> NexusClientHandle<R> getHandle(
      String operationId,
      @Nullable String operationRunId,
      Class<R> resultClass,
      @Nullable Type resultType) {
    return null;
  }

  @Override
  public UntypedNexusServiceClient newUntypeNexusServiceClient() {
    return null;
  }

  @Override
  public <R> NexusServiceClient<R> newNexusServiceClient() {
    return null;
  }

  @Override
  public StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input) {
    return nexusClientInterceptor.startNexusOperationExecution(input);
  }

  @Override
  public DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input) {
    return nexusClientInterceptor.describeNexusOperationExecution(input);
  }

  @Override
  public CompletableFuture<DescribeNexusOperationExecutionOutput>
      describeNexusOperationExecutionAsync(DescribeNexusOperationExecutionInput input) {
    return nexusClientInterceptor.describeNexusOperationExecutionAsync(input);
  }

  @Override
  public PollNexusOperationExecutionOutput pollNexusOperationExecution(
      PollNexusOperationExecutionInput input) {
    return nexusClientInterceptor.pollNexusOperationExecution(input);
  }

  @Override
  public CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input) {
    return nexusClientInterceptor.pollNexusOperationExecutionAsync(input);
  }

  @Override
  public ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input) {
    return nexusClientInterceptor.listNexusOperationExecutions(input);
  }

  @Override
  public CountNexusOperationExecutionsOutput countNexusOperationExecutions(
      CountNexusOperationExecutionsInput input) {
    return nexusClientInterceptor.countNexusOperationExecutions(input);
  }

  @Override
  public void requestCancelNexusOperationExecution(
      RequestCancelNexusOperationExecutionInput input) {
    nexusClientInterceptor.requestCancelNexusOperationExecution(input);
  }

  @Override
  public void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input) {
    nexusClientInterceptor.terminateNexusOperationExecution(input);
  }

  @Override
  public void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input) {
    nexusClientInterceptor.deleteNexusOperationExecution(input);
  }
}
