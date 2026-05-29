package io.temporal.internal.client;

import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.failure.v1.Failure;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.DescribeNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.DescribeNexusOperationExecutionOutput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.PollNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.PollNexusOperationExecutionOutput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.RequestCancelNexusOperationExecutionInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.TerminateNexusOperationExecutionInput;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
  private final DataConverter dataConverter;

  public NexusOperationHandleImpl(
      String operationId,
      @Nullable String runId,
      NexusClientCallsInterceptor interceptor,
      DataConverter dataConverter) {
    if (operationId == null) {
      throw new IllegalArgumentException("operationId is required");
    }
    if (interceptor == null) {
      throw new IllegalArgumentException("interceptor is required");
    }
    if (dataConverter == null) {
      throw new IllegalArgumentException("dataConverter is required");
    }
    this.operationId = operationId;
    this.runId = runId;
    this.interceptor = interceptor;
    this.dataConverter = dataConverter;
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
    PollNexusOperationExecutionOutput out =
        pollSyncUntilCompletedOrDeadline(Deadline.after(timeout, unit));
    return extractResult(out, resultClass, resultType);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass) {
    return getResultAsync(timeout, unit, resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType) {
    Deadline deadline = Deadline.after(timeout, unit);
    return pollAsyncUntilCompletedOrDeadline(deadline)
        .handle(
            (out, e) -> {
              if (e == null) {
                return extractResult(out, resultClass, resultType);
              }
              throw mapAsyncException(e, deadline);
            });
  }

  private PollNexusOperationExecutionOutput pollSyncUntilCompletedOrDeadline(Deadline deadline)
      throws TimeoutException {
    while (true) {
      PollNexusOperationExecutionInput pollInput =
          new PollNexusOperationExecutionInput(operationId, runId, deadline);
      PollNexusOperationExecutionOutput out;
      try {
        out = interceptor.pollNexusOperationExecution(pollInput);
      } catch (StatusRuntimeException e) {
        if (deadline.isExpired() && Status.Code.DEADLINE_EXCEEDED.equals(e.getStatus().getCode())) {
          throw new TimeoutException("getResult timed out before the operation completed");
        }
        throw e;
      }
      if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
        return out;
      }
    }
  }

  private CompletableFuture<PollNexusOperationExecutionOutput> pollAsyncUntilCompletedOrDeadline(
      Deadline deadline) {
    PollNexusOperationExecutionInput pollInput =
        new PollNexusOperationExecutionInput(operationId, runId, deadline);
    CompletableFuture<PollNexusOperationExecutionOutput> pollFuture;
    try {
      pollFuture = interceptor.pollNexusOperationExecutionAsync(pollInput);
    } catch (Throwable t) {
      pollFuture = new CompletableFuture<>();
      pollFuture.completeExceptionally(t);
    }
    return pollFuture.thenCompose(
        out -> {
          if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
            return CompletableFuture.completedFuture(out);
          }
          return pollAsyncUntilCompletedOrDeadline(deadline);
        });
  }

  private static CompletionException mapAsyncException(Throwable e, Deadline deadline) {
    Throwable cause = e instanceof CompletionException ? e.getCause() : e;
    if (deadline.isExpired()
        && cause instanceof StatusRuntimeException
        && Status.Code.DEADLINE_EXCEEDED.equals(
            ((StatusRuntimeException) cause).getStatus().getCode())) {
      return new CompletionException(
          new TimeoutException("getResultAsync timed out before the operation completed"));
    }
    return e instanceof CompletionException ? (CompletionException) e : new CompletionException(e);
  }

  private <R> R extractResult(
      PollNexusOperationExecutionOutput out, Class<R> resultClass, @Nullable Type resultType) {
    Optional<Failure> failure = out.getFailure();
    if (failure.isPresent()) {
      throw new NexusOperationFailedException(
          "Nexus operation failed: operationId='" + operationId + "'",
          operationId,
          runId,
          dataConverter.failureToException(failure.get()));
    }
    Optional<Payload> payload = out.getResult();
    if (!payload.isPresent()) {
      return null;
    }
    return dataConverter.fromPayload(
        payload.get(), resultClass, resultType != null ? resultType : resultClass);
  }
}
