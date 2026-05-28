package io.temporal.client;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.nexusrpc.OperationDefinition;
import io.nexusrpc.ServiceDefinition;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.util.MethodExtractor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.util.function.BiFunction;

/**
 * Typed Nexus service client. Extracts the operation name from a {@link BiFunction} that targets a
 * method on the service interface (via a {@link Proxy} of {@code T}) and delegates the start RPC to
 * the interceptor chain inherited from the underlying {@link NexusClient}.
 */
@Experimental
class NexusServiceClientImpl<T> extends UntypedNexusServiceClientImpl
    implements NexusServiceClient<T> {

  private final Class<T> serviceInterface;
  private final ServiceDefinition serviceDef;

  static <T> NexusServiceClient<T> newInstance(
      Class<T> service, String endpoint, WorkflowServiceStubs stubs, NexusClientOptions options) {
    enforceNonWorkflowThread();
    // Build the underlying NexusClient impl directly (bypassing the wrapped factory) so we can
    // hand its interceptor chain to the service client. The outer service-client proxy below
    // still enforces the non-workflow-thread check at every call.
    NexusClientImpl rawClient = new NexusClientImpl(stubs, options);
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new NexusServiceClientImpl<>(
            rawClient.getNexusClientCallsInvoker(), service, endpoint, options),
        NexusServiceClient.class);
  }

  NexusServiceClientImpl(
      NexusClientCallsInterceptor invoker,
      Class<T> serviceInterface,
      String endpoint,
      NexusClientOptions options) {
    this(
        invoker,
        serviceInterface,
        ServiceDefinition.fromClass(serviceInterface),
        endpoint,
        options);
  }

  private NexusServiceClientImpl(
      NexusClientCallsInterceptor invoker,
      Class<T> serviceInterface,
      ServiceDefinition serviceDef,
      String endpoint,
      NexusClientOptions options) {
    super(invoker, endpoint, serviceDef.getName(), options);
    this.serviceInterface = serviceInterface;
    this.serviceDef = serviceDef;
  }

  @Override
  public <U, R> NexusOperationHandle<R> start(
      BiFunction<T, U, R> operation, U input, StartNexusOperationOptions options) {
    Method method =
        MethodExtractor.extract(serviceInterface, (Functions.Func2<T, U, R>) operation::apply);
    OperationDefinition opDef =
        serviceDef.getOperations().values().stream()
            .filter(o -> method.getName().equals(o.getMethodName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Method "
                            + method.getName()
                            + " is not a Nexus operation on "
                            + serviceInterface.getName()));
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    UntypedNexusOperationHandle untyped = start(opDef.getName(), options, input);
    return NexusOperationHandle.fromUntyped(untyped, resultClass, method.getGenericReturnType());
  }
}
