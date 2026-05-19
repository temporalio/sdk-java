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
 * Single implementation of {@link NexusClientHandle}/{@link UntypedNexusClientHandle}. Constructed
 * untyped by {@link NexusClient#getHandle(String)} and bound to a result type via {@link
 * NexusClientHandle#fromUntyped}.
 */
public class NexusClientHandleImpl<R> implements NexusClientHandle<R> {

  /** Default deadline applied to per-handle non-poll RPCs (e.g. {@code describe}). */
  private static final long DEFAULT_DEADLINE_SECONDS = 30;

  /**
   * Per-poll deadline used by {@link #getResult} and {@link #getResultAsync}. The server holds the
   * request up to this long waiting for completion; if the operation hasn't finished, we re-poll.
   */
  private static final long POLL_DEADLINE_SECONDS = 60;

  final NexusClientCallsInterceptor interceptor;
  final String operationId;
  final @Nullable String runId;
  final DataConverter dataConverter;
  final @Nullable Class<R> resultClass;
  final @Nullable Type resultType;

  /** Construct an untyped handle. Used by {@link NexusClientImpl#getHandle}. */
  public NexusClientHandleImpl(
      NexusClientCallsInterceptor interceptor,
      String operationId,
      @Nullable String runId,
      DataConverter dataConverter) {
    this(interceptor, operationId, runId, dataConverter, null, null);
  }

  /**
   * Implementation of {@link NexusClientHandle#fromUntyped(UntypedNexusClientHandle, Class, Type)}.
   * Lives here so the interface doesn't reach into impl-private state.
   */
  static <R> NexusClientHandle<R> fromUntyped(
      UntypedNexusClientHandle handle, Class<R> resultClass, @Nullable Type resultType) {
    if (!(handle instanceof NexusClientHandleImpl)) {
      throw new IllegalArgumentException(
          "Unsupported handle implementation: " + handle.getClass().getName());
    }
    NexusClientHandleImpl<?> source = (NexusClientHandleImpl<?>) handle;
    return new NexusClientHandleImpl<>(
        source.interceptor,
        source.operationId,
        source.runId,
        source.dataConverter,
        resultClass,
        resultType);
  }

  /** Construct a typed handle. Use {@link NexusClientHandle#fromUntyped} from caller code. */
  NexusClientHandleImpl(
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
  public NexusClientOperationExecutionDescription describe() {
    DescribeNexusOperationExecutionInput input =
        new DescribeNexusOperationExecutionInput(
            operationId,
            runId,
            /* includeInput= */ false,
            /* includeOutcome= */ true,
            Deadline.after(DEFAULT_DEADLINE_SECONDS, TimeUnit.SECONDS));
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
    PollNexusOperationExecutionOutput out = pollUntilCompleted();
    return extractResult(out, resultClass, resultType);
  }

  @Override
  public <X> CompletableFuture<X> getResultAsync(Class<X> resultClass) {
    return getResultAsync(resultClass, null);
  }

  @Override
  public <X> CompletableFuture<X> getResultAsync(Class<X> resultClass, @Nullable Type resultType) {
    return pollAsyncUntilCompleted().thenApply(out -> extractResult(out, resultClass, resultType));
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
    long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
    PollNexusOperationExecutionOutput out = pollSyncUntilCompletedOrDeadline(deadlineNanos);
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
    long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
    return pollAsyncUntilCompletedOrDeadline(deadlineNanos)
        .thenApply(out -> extractResult(out, resultClass, resultType));
  }

  @Override
  public R getResult() {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResult() requires a result type binding — wrap this handle with NexusClientHandle.fromUntyped");
    }
    return getResult(resultClass, resultType);
  }

  @Override
  public R getResult(long timeout, TimeUnit unit) throws TimeoutException {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResult() requires a result type binding — wrap this handle with NexusClientHandle.fromUntyped");
    }
    return getResult(timeout, unit, resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync() {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResultAsync() requires a result type binding — wrap this handle with NexusClientHandle.fromUntyped");
    }
    return getResultAsync(resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit) {
    if (resultClass == null) {
      throw new IllegalStateException(
          "getResultAsync() requires a result type binding — wrap this handle with NexusClientHandle.fromUntyped");
    }
    return getResultAsync(timeout, unit, resultClass, resultType);
  }

  /** Long-poll loop: re-poll if the server returns before the operation completes. */
  private PollNexusOperationExecutionOutput pollUntilCompleted() {
    while (true) {
      PollNexusOperationExecutionOutput out =
          interceptor.pollNexusOperationExecution(buildPollInput());
      if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
        return out;
      }
    }
  }

  /** Async long-poll loop using {@code thenCompose} to recurse without blocking a thread. */
  private CompletableFuture<PollNexusOperationExecutionOutput> pollAsyncUntilCompleted() {
    return interceptor
        .pollNexusOperationExecutionAsync(buildPollInput())
        .thenCompose(
            out -> {
              if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
                return CompletableFuture.completedFuture(out);
              }
              return pollAsyncUntilCompleted();
            });
  }

  /** Sync poll loop bounded by an absolute nanos deadline. */
  private PollNexusOperationExecutionOutput pollSyncUntilCompletedOrDeadline(long deadlineNanos)
      throws TimeoutException {
    while (true) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new TimeoutException("getResult timed out before the operation completed");
      }
      long pollDeadlineNanos =
          Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(POLL_DEADLINE_SECONDS));
      PollNexusOperationExecutionInput pollInput =
          new PollNexusOperationExecutionInput(
              operationId,
              runId,
              NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED,
              Deadline.after(pollDeadlineNanos, TimeUnit.NANOSECONDS));
      PollNexusOperationExecutionOutput out;
      try {
        out = interceptor.pollNexusOperationExecution(pollInput);
      } catch (RuntimeException e) {
        if (System.nanoTime() >= deadlineNanos) {
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

  /** Async poll loop bounded by an absolute nanos deadline. */
  private CompletableFuture<PollNexusOperationExecutionOutput> pollAsyncUntilCompletedOrDeadline(
      long deadlineNanos) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) {
      CompletableFuture<PollNexusOperationExecutionOutput> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new TimeoutException("getResultAsync timed out before the operation completed"));
      return failed;
    }
    long pollDeadlineNanos =
        Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(POLL_DEADLINE_SECONDS));
    PollNexusOperationExecutionInput pollInput =
        new PollNexusOperationExecutionInput(
            operationId,
            runId,
            NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED,
            Deadline.after(pollDeadlineNanos, TimeUnit.NANOSECONDS));
    return interceptor
        .pollNexusOperationExecutionAsync(pollInput)
        .thenCompose(
            out -> {
              if (out.getWaitStage() == NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED) {
                return CompletableFuture.completedFuture(out);
              }
              return pollAsyncUntilCompletedOrDeadline(deadlineNanos);
            });
  }

  private PollNexusOperationExecutionInput buildPollInput() {
    return new PollNexusOperationExecutionInput(
        operationId,
        runId,
        NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED,
        Deadline.after(POLL_DEADLINE_SECONDS, TimeUnit.SECONDS));
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
