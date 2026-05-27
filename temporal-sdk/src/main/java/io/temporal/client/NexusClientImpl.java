package io.temporal.client;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import com.google.protobuf.ByteString;
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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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
  public UntypedNexusOperationHandle getHandle(String operationId) {
    return getHandle(operationId, null);
  }

  @Override
  public UntypedNexusOperationHandle getHandle(String operationId, @Nullable String runId) {
    return new NexusOperationHandleImpl(
        operationId, runId, nexusClientCallsInvoker, options.getDataConverter());
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
  public UntypedNexusServiceClient newUntypedNexusServiceClient(
      String endpoint, String serviceName) {
    return new UntypedNexusServiceClientImpl(
        nexusClientCallsInvoker, endpoint, serviceName, options);
  }

  /**
   * Returns the head of the interceptor chain. Package-private so service-client builders can route
   * start RPCs through the chain without exposing it on the public {@link NexusClient} interface.
   */
  NexusClientCallsInterceptor getNexusClientCallsInvoker() {
    return nexusClientCallsInvoker;
  }

  private static final int DEFAULT_LIST_PAGE_SIZE = 1000;

  @Override
  public Stream<NexusOperationExecutionMetadata> listNexusOperationExecutions(
      @Nullable String query) {
    Iterator<NexusOperationExecutionMetadata> iter =
        new ListPageIterator(nexusClientCallsInvoker, query, DEFAULT_LIST_PAGE_SIZE);
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iter, Spliterator.ORDERED | Spliterator.NONNULL),
        false);
  }

  @Override
  public NexusOperationExecutionCount countNexusOperationExecutions(@Nullable String query) {
    CountNexusOperationExecutionsOutput out =
        nexusClientCallsInvoker.countNexusOperationExecutions(
            new CountNexusOperationExecutionsInput(query));
    List<NexusOperationExecutionCount.AggregationGroup> publicGroups =
        out.getGroups().stream()
            .map(
                g ->
                    new NexusOperationExecutionCount.AggregationGroup(
                        g.getCount(), g.getGroupValues()))
            .collect(Collectors.toList());
    return new NexusOperationExecutionCount(out.getCount(), publicGroups);
  }

  /** Lazily fetches pages from the interceptor and flattens them into a single iteration. */
  private static final class ListPageIterator implements Iterator<NexusOperationExecutionMetadata> {
    private final NexusClientCallsInterceptor invoker;
    private final @Nullable String query;
    private final int pageSize;
    private Iterator<NexusOperationExecutionMetadata> current =
        java.util.Collections.emptyIterator();
    private @Nullable ByteString nextPageToken = null;
    private boolean exhausted = false;

    ListPageIterator(NexusClientCallsInterceptor invoker, @Nullable String query, int pageSize) {
      this.invoker = invoker;
      this.query = query;
      this.pageSize = pageSize;
    }

    @Override
    public boolean hasNext() {
      while (!current.hasNext() && !exhausted) {
        fetchNextPage();
      }
      return current.hasNext();
    }

    @Override
    public NexusOperationExecutionMetadata next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return current.next();
    }

    private void fetchNextPage() {
      ListNexusOperationExecutionsOutput page =
          invoker.listNexusOperationExecutions(
              new ListNexusOperationExecutionsInput(query, pageSize, nextPageToken));
      current =
          page.getOperations().stream()
              .map(NexusOperationExecutionMetadata::fromListInfo)
              .iterator();
      ByteString token = page.getNextPageToken();
      if (token == null || token.isEmpty()) {
        exhausted = true;
        nextPageToken = null;
      } else {
        nextPageToken = token;
      }
    }
  }
}
