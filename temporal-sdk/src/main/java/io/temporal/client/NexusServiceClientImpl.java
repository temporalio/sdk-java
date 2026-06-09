package io.temporal.client;

import io.nexusrpc.OperationDefinition;
import io.nexusrpc.ServiceDefinition;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.internal.util.MethodExtractor;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Typed Nexus service client. Extracts the operation name from a {@link Functions.Func2} that
 * targets a method on the service interface (via a {@link Proxy} of {@code T}) and delegates the
 * start RPC to the interceptor chain inherited from the underlying {@link NexusClient}.
 */
@Experimental
class NexusServiceClientImpl<T> extends UntypedNexusServiceClientImpl
    implements NexusServiceClient<T> {

  private final Class<T> serviceInterface;
  private final ServiceDefinition serviceDef;

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
      Functions.Func2<T, U, R> operation, StartNexusOperationOptions options, U input) {
    Method method = MethodExtractor.extract(serviceInterface, operation);
    return startResolved(method, input, options);
  }

  @Override
  public <U, R> R execute(
      Functions.Func2<T, U, R> operation, StartNexusOperationOptions options, U input) {
    return start(operation, options, input).getResult();
  }

  @Override
  public <U, R> CompletableFuture<R> executeAsync(
      Functions.Func2<T, U, R> operation, StartNexusOperationOptions options, U input) {
    return start(operation, options, input).getResultAsync();
  }

  @Override
  public <R> NexusOperationHandle<R> start(
      Functions.Func1<T, R> operation, StartNexusOperationOptions options) {
    Method method = MethodExtractor.extract(serviceInterface, operation);
    return startResolved(method, null, options);
  }

  @Override
  public <R> R execute(Functions.Func1<T, R> operation, StartNexusOperationOptions options) {
    return start(operation, options).getResult();
  }

  @Override
  public <R> CompletableFuture<R> executeAsync(
      Functions.Func1<T, R> operation, StartNexusOperationOptions options) {
    return start(operation, options).getResultAsync();
  }

  /**
   * Shared back-end for the typed start variants: resolves the method to its Nexus {@code
   * OperationDefinition}, issues the start RPC, and wraps the resulting untyped handle in a typed
   * one. {@code input} may be {@code null} for no-input operations.
   */
  private <R> NexusOperationHandle<R> startResolved(
      Method method, @Nullable Object input, StartNexusOperationOptions options) {
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
