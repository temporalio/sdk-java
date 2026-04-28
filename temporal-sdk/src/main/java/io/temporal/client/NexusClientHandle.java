package io.temporal.client;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

public interface NexusClientHandle<R> extends UntypedNexusClientHandle {
    public <R> NexusClientHandle<R> fromUntyped(
            UntypedNexusClientHandle handle, Class<R> resultClass);
    public <R> NexusClientHandle<R> fromUntyped(
            UntypedNexusClientHandle handle,
            Class<R> resultClass,
            @Nullable Type resultType);

    public R getResult();
    public CompletableFuture<R> getResultAsync();
}
