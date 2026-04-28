package io.temporal.client;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

public class NexusClientHandleImpl<R> implements NexusClientHandle<R> {

    private final NexusClientInterceptor interceptor;

    public (NexusClientInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    public <T> NexusClientHandle<T> fromUntyped(
            UntypedNexusClientHandle handle, Class<T> resultClass) {
        return null;
    }

    public <T> NexusClientHandle<T> fromUntyped(
            UntypedNexusClientHandle handle,
            Class<T> resultClass,
            @Nullable Type resultType) {
        return null;
    }

    public R getResult() {
        return null;
    }

    public CompletableFuture<R> getResultAsync() {
        return null;
    }

    @Override
    public @Nullable String getNexusOperationRunId() {
        return "";
    }

    @Override
    public <R> R getResult(Class<R> resultClass) {
        return null;
    }

    @Override
    public <R> R getResult(Class<R> resultClass, @Nullable Type resultType) {
        return null;
    }

    @Override
    public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
        return null;
    }

    @Override
    public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType) {
        return null;
    }

    @Override
    public  describe() {
        return null;
    }

    @Override
    public void cancel() {

    }

    @Override
    public void cancel(@Nullable String reason) {

    }

    @Override
    public void terminate() {

    }

    @Override
    public void terminate(@Nullable String reason) {

    }

}
