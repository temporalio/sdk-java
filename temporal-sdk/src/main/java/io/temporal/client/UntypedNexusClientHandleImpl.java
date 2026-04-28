package io.temporal.client;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

public class UntypedNexusClientHandleImpl implements UntypedNexusClientHandle {
    private static final Logger LOGGER = LoggerFactory.getLogger(UntypedNexusClientHandleImpl.class);

    //TODO - EVAN - implement methods
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
    public NexusClientOperationExecutionDescription describe() {
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
