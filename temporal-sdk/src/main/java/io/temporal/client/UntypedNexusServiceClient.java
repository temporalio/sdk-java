package io.temporal.client;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

/** Untyped client for invoking standalone Nexus operations by operation-name string. */
@Experimental
public interface UntypedNexusServiceClient {

  /** Start an operation by name, returning an untyped handle. */
  UntypedNexusClientHandle start(
      String operation, StartNexusOperationOptions options, @Nullable Object arg);

  /** Execute an operation synchronously by name. */
  <R> R execute(
      String operation,
      Class<R> resultClass,
      StartNexusOperationOptions options,
      @Nullable Object arg);

  /** Execute an operation synchronously by name with explicit generic-result {@link Type}. */
  <R> R execute(
      String operation,
      Class<R> resultClass,
      Type resultType,
      StartNexusOperationOptions options,
      @Nullable Object arg);
}
