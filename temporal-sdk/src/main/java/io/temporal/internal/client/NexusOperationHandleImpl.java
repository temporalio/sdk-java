package io.temporal.internal.client;

import io.grpc.Deadline;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.DescribeNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.DescribeNexusOperationExecutionOutput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.GetNexusOperationResultInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.GetNexusOperationResultOutput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.RequestCancelNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.TerminateNexusOperationExecutionInput;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Implementation of {@link UntypedNexusOperationHandle} that delegates lifecycle operations through
 * the interceptor chain.
 */
public final class NexusOperationHandleImpl implements UntypedNexusOperationHandle {

  private final String operationId;
  private final @Nullable String runId;
  private final NexusClientCallsInterceptor interceptor;
  // What the operation was started on, retained so its result and failure are decoded with the
  // same serialization context that encoded them. All null for a handle obtained by operation ID,
  // which never saw a start request.
  private final @Nullable String endpoint;
  private final @Nullable String service;
  private final @Nullable String operation;

  public NexusOperationHandleImpl(
      String operationId, @Nullable String runId, NexusClientCallsInterceptor interceptor) {
    this(operationId, runId, interceptor, null, null, null);
  }

  public NexusOperationHandleImpl(
      String operationId,
      @Nullable String runId,
      NexusClientCallsInterceptor interceptor,
      @Nullable String endpoint,
      @Nullable String service,
      @Nullable String operation) {
    if (operationId == null) {
      throw new IllegalArgumentException("operationId is required");
    }
    if (interceptor == null) {
      throw new IllegalArgumentException("interceptor is required");
    }
    this.operationId = operationId;
    this.runId = runId;
    this.interceptor = interceptor;
    this.endpoint = endpoint;
    this.service = service;
    this.operation = operation;
  }

  @Override
  public String getNexusOperationId() {
    return operationId;
  }

  @Override
  public @Nullable String getNexusOperationRunId() {
    return runId;
  }

  @Override
  public NexusOperationExecutionDescription describe() {
    DescribeNexusOperationExecutionInput input =
        new DescribeNexusOperationExecutionInput(operationId, runId);
    DescribeNexusOperationExecutionOutput output =
        interceptor.describeNexusOperationExecution(input);
    return output.getDescription();
  }

  @Override
  public void cancel() {
    cancel(null);
  }

  @Override
  public void cancel(@Nullable String reason) {
    interceptor.requestCancelNexusOperationExecution(
        new RequestCancelNexusOperationExecutionInput(operationId, runId, reason));
  }

  @Override
  public void terminate() {
    terminate(null);
  }

  @Override
  public void terminate(@Nullable String reason) {
    interceptor.terminateNexusOperationExecution(
        new TerminateNexusOperationExecutionInput(operationId, runId, reason));
  }

  @Override
  public <R> R getResult(Class<R> resultClass) {
    return getResult(resultClass, null);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, @Nullable Type resultType) {
    try {
      return getResult(Integer.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
    } catch (TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
    return getResultAsync(resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType) {
    return getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
      throws TimeoutException {
    return getResult(timeout, unit, resultClass, null);
  }

  @Override
  public <R> R getResult(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType)
      throws TimeoutException {
    GetNexusOperationResultInput<R> input =
        new GetNexusOperationResultInput<>(
            operationId,
            runId,
            Deadline.after(timeout, unit),
            resultClass,
            resultType,
            endpoint,
            service,
            operation);
    return interceptor.getNexusOperationResult(input).getResult();
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass) {
    return getResultAsync(timeout, unit, resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType) {
    GetNexusOperationResultInput<R> input =
        new GetNexusOperationResultInput<>(
            operationId,
            runId,
            Deadline.after(timeout, unit),
            resultClass,
            resultType,
            endpoint,
            service,
            operation);
    return interceptor
        .getNexusOperationResultAsync(input)
        .thenApply(GetNexusOperationResultOutput::getResult);
  }
}
