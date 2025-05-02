package io.temporal.internal.worker;

public interface WorkerWithLifecycle {
  WorkerLifecycleState getLifecycleState();
}
