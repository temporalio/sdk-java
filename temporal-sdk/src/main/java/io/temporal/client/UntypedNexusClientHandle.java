package io.temporal.client;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

public interface UntypedNexusClientHandle {
    /// Present if the handle was returned by `start` method
    /// or if it was set when calling `getNexusOperationHandle`.
    /// Null if `getNexusOperationHandle` was called with null run ID
    /// - in that case, use `describe` to get current run ID.
    @Nullable
    String getNexusOperationRunId();

    <R> R getResult(Class<R> resultClass);
    <R> R getResult(Class<R> resultClass, @Nullable Type resultType);
    <R> CompletableFuture<R> getResultAsync(Class<R> resultClass);
    <R> CompletableFuture<R> getResultAsync(
            Class<R> resultClass, @Nullable Type resultType);
    NexusClientOperationExecutionDescription describe();
    void cancel();
    void cancel(@Nullable String reason);
    void terminate();
    void terminate(@Nullable String reason);
}
