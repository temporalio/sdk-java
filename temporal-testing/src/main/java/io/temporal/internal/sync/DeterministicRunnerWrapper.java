package io.temporal.internal.sync;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.*;

public class DeterministicRunnerWrapper implements InvocationHandler {
  private final InvocationHandler invocationHandler;
  private final WorkflowThreadExecutor workflowThreadExecutor;

  public DeterministicRunnerWrapper(
      InvocationHandler invocationHandler, WorkflowThreadExecutor workflowThreadExecutor) {
    this.invocationHandler = Objects.requireNonNull(invocationHandler);
    this.workflowThreadExecutor = Objects.requireNonNull(workflowThreadExecutor);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    CompletableFuture<Object> result = new CompletableFuture<>();
    DeterministicRunner runner =
        new DeterministicRunnerImpl(
            workflowThreadExecutor,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              try {
                result.complete(invocationHandler.invoke(proxy, method, args));
              } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
              }
            });
    // Used to execute activities under TestActivityEnvironment
    // So it is expected that a workflow thread is blocked for a long time.
    runner.runUntilAllBlocked(Long.MAX_VALUE);
    try {
      return result.get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }
}
