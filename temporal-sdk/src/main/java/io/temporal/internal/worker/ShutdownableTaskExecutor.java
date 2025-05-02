package io.temporal.internal.worker;

public interface ShutdownableTaskExecutor<T> extends TaskExecutor<T>, Shutdownable {}
