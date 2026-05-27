package io.temporal.client;

import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.failure.v1.Failure;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Single implementation of {@link NexusOperationHandle}/{@link UntypedNexusOperationHandle}.
 * Constructed untyped by {@link NexusClient#getHandle(String)} and bound to a result type via
 * {@link NexusOperationHandle#fromUntyped}.
 */
public class NexusOperationHandleImpl<R> implements NexusOperationHandle<R> {

  final NexusClientCallsInterceptor interceptor;
  final String operationId;
  final @Nullable String runId;
  final DataConverter dataConverter;
  final @Nullable Class<R> resultClass;
  final @Nullable Type resultType;

  /** Construct an untyped handle. Used by {@link NexusClientImpl#getHandle}. */
  public NexusOperationHandleImpl(
      NexusClientCallsInterceptor interceptor,
      String operationId,
      @Nullable String runId,
      DataConverter dataConverter) {
    this(interceptor, operationId, runId, dataConverter, null, null);
  }

  /**
   * Implementation of {@link NexusOperationHandle#fromUntyped(UntypedNexusOperationHandle, Class,
   * Type)}. Lives here so the interface doesn't reach into impl-private state.
   */
  static <R> NexusOperationHandle<R> fromUntyped(
      UntypedNexusOperationHandle handle, Class<R> resultClass, @Nullable Type resultType) {
    if (!(handle instanceof NexusOperationHandleImpl)) {
      throw new IllegalArgumentException(
          "Unsupported handle implementation: " + handle.getClass().getName());
    }
    NexusOperationHandleImpl<?> source = (NexusOperationHandleImpl<?>) handle;
    return new NexusOperationHandleImpl<>(
        source.interceptor,
        source.operationId,
        source.runId,
        source.dataConverter,
        resultClass,
        resultType);
  }

  /** Construct a typed handle. Use {@link NexusOperationHandle#fromUntyped} from caller code. */
  NexusOperationHandleImpl(
      NexusClientCallsInterceptor interceptor,
      String operationId,
      @Nullable String runId,
      DataConverter dataConverter,
      @Nullable Class<R> resultClass,
      @Nullable Type resultType) {
    if (interceptor == null) {
      throw new IllegalArgumentException("interceptor is required");
    }
    if (operationId == null) {
      throw new IllegalArgumentException("operationId is required");
    }
    if (dataConverter == null) {
      throw new IllegalArgumentException("dataConverter is required");
    }
    this.interceptor = interceptor;
    this.operationId = operationId;
    this.runId = runId;
    this.dataConverter = dataConverter;
    this.resultClass = resultClass;
    this.resultType = resultType;
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
        new DescribeNexusOperationExecutionInput(
            operationId, runId, /* includeInput= */ false, /* includeOutcome= */ true);
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
  public <X> X getResult(Class<X> resultClass) {
    return getResult(resultClass, null);
  }

  @Override
  public <X> X getResult(Class<X> resultClass, @Nullable Type resultType) {
    try {
      return getResult(Integer.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
    } catch (TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public <X> CompletableFuture<X> getResultAsync(Class<X> resultClass) {
    return getResultAsync(resultClass, null);
  }

  @Override
  public <X> CompletableFuture<X> getResultAsync(Class<X> resultClass, @Nullable Type resultType) {
    return getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
  }

  @Override
  public <X> X getResult(long timeout, TimeUnit unit, Class<X> resultClass)
      throws TimeoutException {
    return getResult(timeout, unit, resultClass, null);
  }

  @Override
  public <X> X getResult(
      long timeout, TimeUnit unit, Class<X> resultClass, @Nullable Type resultType)
      throws TimeoutException {
    PollNexusOperationExecutionOutput out =
        pollSyncUntilCompletedOrDeadline(Deadline.after(timeout, unit));
    return extractResult(out, resultClass, resultType);
  }

  @Override
  public <X> CompletableFuture<X> getResultAsync(
      long timeout, TimeUnit unit, Class<X> resultClass) {
    return getResultAsync(timeout, unit, resultClass, null);
  }

  @Override
  public <X> CompletableFuture<X> getResultAsync(
      long timeout, TimeUnit unit, Class<X> resultClass, @Nullable Type resultType) {
    return pollAsyncUntilCompletedOrDeadline(Deadline.after(timeout, unit))
        .thenApply(out -> extractResult(out, resultClass, resultType));
  }

  @Override
  public R getResult() {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResult() requires a result type binding — wrap this handle with NexusOperationHandle.fromUntyped");
    }
    return getResult(resultClass, resultType);
  }

  @Override
  public R getResult(long timeout, TimeUnit unit) throws TimeoutException {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResult() requires a result type binding — wrap this handle with NexusOperationHandle.fromUntyped");
    }
    return getResult(timeout, unit, resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync() {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResultAsync() requires a result type binding — wrap this handle with NexusOperationHandle.fromUntyped");
    }
    return getResultAsync(resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit) {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResultAsync() requires a result type binding — wrap this handle with NexusOperationHandle.fromUntyped");
    }
    return getResultAsync(timeout, unit, resultClass, resultType);
  }

  /** Sync long-poll loop bounded by {@code deadline}. */
  private PollNexusOperationExecutionOutput pollSyncUntilCompletedOrDeadline(Deadline deadline)
      throws TimeoutException {
    while (true) {
      PollNexusOperationExecutionInput pollInput =
          new PollNexusOperationExecutionInput(
              operationId,
              runId,
              NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED,
              deadline);
      PollNexusOperationExecutionOutput out;
      try {
        out = interceptor.pollNexusOperationExecution(pollInput);
      } catch (RuntimeException e) {
        if (deadline.isExpired()) {
          TimeoutException timeout =
              new TimeoutException("getResult timed out before the operation completed");
          timeout.initCause(e);
          throw timeout;
        }
        throw e;
      }
      if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
        return out;
      }
    }
  }

  /** Async long-poll loop bounded by {@code deadline}, recursing via {@code thenCompose}. */
  private CompletableFuture<PollNexusOperationExecutionOutput> pollAsyncUntilCompletedOrDeadline(
      Deadline deadline) {
    if (deadline.isExpired()) {
      CompletableFuture<PollNexusOperationExecutionOutput> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new TimeoutException("getResultAsync timed out before the operation completed"));
      return failed;
    }
    PollNexusOperationExecutionInput pollInput =
        new PollNexusOperationExecutionInput(
            operationId,
            runId,
            NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED,
            deadline);
    return interceptor
        .pollNexusOperationExecutionAsync(pollInput)
        .thenCompose(
            out -> {
              if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
                return CompletableFuture.completedFuture(out);
              }
              return pollAsyncUntilCompletedOrDeadline(deadline);
            });
  }

  /**
   * Convert a completed poll response into the typed result, throwing the operation's failure as an
   * exception if it failed.
   */
  private <X> X extractResult(
      PollNexusOperationExecutionOutput out, Class<X> resultClass, @Nullable Type resultType) {
    Optional<Failure> failure = out.getFailure();
    if (failure.isPresent()) {
      throw dataConverter.failureToException(failure.get());
    }
    Optional<Payload> payload = out.getResult();
    if (!payload.isPresent()) {
      return null;
    }
    return dataConverter.fromPayload(
        payload.get(), resultClass, resultType != null ? resultType : resultClass);
  }
}
