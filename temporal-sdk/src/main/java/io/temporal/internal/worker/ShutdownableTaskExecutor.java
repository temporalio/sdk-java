package io.temporal.internal.worker;

/** Specialization of {@link TaskExecutor} that can be shutdown. This is used by the */
public interface ShutdownableTaskExecutor<T> extends TaskExecutor<T>, Shutdownable {}
