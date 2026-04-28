package io.temporal.client;

import io.temporal.workflow.NexusOperationOptions;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

public interface UntypedNexusServiceClient {

  UntypedNexusClientHandle start(
      String operation, NexusOperationOptions options, @Nullable Object arg);

  <R> R execute(
      String operation, Class<R> resultClass, NexusOperationOptions options, @Nullable Object arg);

  <R> R execute(
      String operation,
      Class<R> resultClass,
      Type resultType,
      NexusOperationOptions options,
      @Nullable Object arg);
}
