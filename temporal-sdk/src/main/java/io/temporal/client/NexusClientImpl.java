package io.temporal.client;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import com.uber.m3.tally.Scope;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.CountNexusOperationExecutionsInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.CountNexusOperationExecutionsOutput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.ListNexusOperationExecutionsInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.ListNexusOperationExecutionsOutput;
import io.temporal.common.interceptors.NexusClientInterceptor;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.client.NamespaceInjectWorkflowServiceStubs;
import io.temporal.internal.client.NexusOperationHandleImpl;
import io.temporal.internal.client.RootNexusClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Experimental
public class NexusClientImpl implements NexusClient {

  private static final Logger log = LoggerFactory.getLogger(NexusClientImpl.class);

  private final WorkflowServiceStubs workflowServiceStubs;
  private final NexusClientOptions options;
  private final GenericWorkflowClient genericClient;
  private final Scope metricsScope;
  private final NexusClientCallsInterceptor nexusClientCallsInvoker;
  private final List<NexusClientInterceptor> interceptors;

  public static NexusClient newInstance(WorkflowServiceStubs service, NexusClientOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new NexusClientImpl(service, options), NexusClient.class);
  }

  NexusClientImpl(WorkflowServiceStubs workflowServiceStubs, NexusClientOptions options) {
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
    this.nexusClientCallsInvoker = initializeClientInvoker();
    if (log.isDebugEnabled()) {
      log.debug(
          "NexusClient initialized: namespace={}, interceptors={}",
          options.getNamespace(),
          interceptors.size());
    }
  }

  private NexusClientCallsInterceptor initializeClientInvoker() {
    NexusClientCallsInterceptor invoker = new RootNexusClientInvoker(genericClient, options);
    for (NexusClientInterceptor clientInterceptor : interceptors) {
      NexusClientCallsInterceptor wrapped = clientInterceptor.nexusClientCallsInterceptor(invoker);
      if (wrapped == null) {
        throw new IllegalStateException(
            "NexusClientInterceptor "
                + clientInterceptor.getClass().getName()
                + " returned null from nexusClientCallsInterceptor; expected a non-null"
                + " NexusClientCallsInterceptor wrapping the supplied next link");
      }
      invoker = wrapped;
    }
    return invoker;
  }

  @Override
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return workflowServiceStubs;
  }

  @Override
  public UntypedNexusOperationHandle getHandle(String operationId, @Nullable String runId) {
    return new NexusOperationHandleImpl(operationId, runId, nexusClientCallsInvoker);
  }

  @Override
  public <R> NexusOperationHandle<R> getHandle(
      String operationId, @Nullable String runId, Class<R> resultClass) {
    return getHandle(operationId, runId, resultClass, null);
  }

  @Override
  public <R> NexusOperationHandle<R> getHandle(
      String operationId,
      @Nullable String runId,
      Class<R> resultClass,
      @Nullable java.lang.reflect.Type resultType) {
    return NexusOperationHandle.fromUntyped(getHandle(operationId, runId), resultClass, resultType);
  }

  @Override
  public <T> NexusServiceClient<T> newNexusServiceClient(Class<T> service, String endpoint) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new NexusServiceClientImpl<>(nexusClientCallsInvoker, service, endpoint, options),
        NexusServiceClient.class);
  }

  @Override
  public UntypedNexusServiceClient newUntypedNexusServiceClient(
      String endpoint, String serviceName) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new UntypedNexusServiceClientImpl(nexusClientCallsInvoker, endpoint, serviceName, options),
        UntypedNexusServiceClient.class);
  }

  @Override
  public Stream<NexusOperationExecutionMetadata> listNexusOperationExecutions(
      @Nullable String query) {
    ListNexusOperationExecutionsOutput out =
        nexusClientCallsInvoker.listNexusOperationExecutions(
            new ListNexusOperationExecutionsInput(query));
    return out.getOperations();
  }

  @Override
  public NexusOperationExecutionCount countNexusOperationExecutions(@Nullable String query) {
    CountNexusOperationExecutionsOutput out =
        nexusClientCallsInvoker.countNexusOperationExecutions(
            new CountNexusOperationExecutionsInput(query));
    return out.getCount();
  }
}
