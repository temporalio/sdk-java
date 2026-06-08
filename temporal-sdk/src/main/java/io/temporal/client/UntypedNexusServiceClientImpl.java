package io.temporal.client;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.StartNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.StartNexusOperationExecutionOutput;
import io.temporal.internal.client.NexusOperationHandleImpl;
import java.lang.reflect.Type;
import java.util.Collections;
import javax.annotation.Nullable;

/**
 * Untyped Nexus service client. Holds the {@link NexusClientCallsInterceptor invoker}, target
 * endpoint, service name, and data converter, and translates operation-name calls into start RPCs
 * routed through the interceptor chain.
 */
@Experimental
class UntypedNexusServiceClientImpl implements UntypedNexusServiceClient {

  private final NexusClientCallsInterceptor invoker;
  private final String endpoint;
  private final String serviceName;
  private final DataConverter dataConverter;

  UntypedNexusServiceClientImpl(
      NexusClientCallsInterceptor invoker,
      String endpoint,
      String serviceName,
      NexusClientOptions clientOptions) {
    if (invoker == null || endpoint == null || serviceName == null || clientOptions == null) {
      throw new IllegalArgumentException(
          "invoker, endpoint, serviceName, and clientOptions are all required");
    }
    this.invoker = invoker;
    this.endpoint = endpoint;
    this.serviceName = serviceName;
    this.dataConverter = clientOptions.getDataConverter();
  }

  @Override
  public UntypedNexusOperationHandle start(
      String operation, StartNexusOperationOptions options, @Nullable Object arg) {
    Payload payload = serializeInput(arg);
    StartNexusOperationExecutionInput input =
        new StartNexusOperationExecutionInput(
            endpoint, serviceName, operation, payload, options, Collections.emptyMap());
    StartNexusOperationExecutionOutput output = invoker.startNexusOperationExecution(input);
    return new NexusOperationHandleImpl(output.getOperationId(), output.getRunId(), invoker);
  }

  @Override
  public <R> R execute(
      String operation,
      Class<R> resultClass,
      StartNexusOperationOptions options,
      @Nullable Object arg) {
    return execute(operation, resultClass, null, options, arg);
  }

  @Override
  public <R> R execute(
      String operation,
      Class<R> resultClass,
      @Nullable Type resultType,
      StartNexusOperationOptions options,
      @Nullable Object arg) {
    UntypedNexusOperationHandle handle = start(operation, options, arg);
    return NexusOperationHandle.fromUntyped(handle, resultClass, resultType).getResult();
  }

  private @Nullable Payload serializeInput(@Nullable Object arg) {
    if (arg == null) {
      return null;
    }
    Class<?> argClass = arg.getClass();
    return dataConverter
        .toPayload(arg)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "DataConverter returned no payload for input of type " + argClass.getName()));
  }
}
