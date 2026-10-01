package io.temporal.internal.sync;

import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.logging.LoggerTag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.slf4j.MDC;

/** A one-shot scheduler entry that borrows the caller's workflow context while it runs. */
final class AsyncTemporalOperation implements DeterministicRunnerEntry {
  private static final ThreadLocal<Boolean> executing = ThreadLocal.withInitial(() -> false);

  static boolean isExecuting() {
    return executing.get();
  }

  private final WorkflowThread owner;
  private final CancellationScopeImpl scope;
  private final int priority;
  private final List<ContextPropagator> contextPropagators;
  private final Map<String, Object> propagatedContexts;
  private boolean done;

  AsyncTemporalOperation(
      WorkflowThread owner,
      CancellationScopeImpl scope,
      int priority,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    this.owner = owner;
    this.scope = scope;
    this.priority = priority;
    this.contextPropagators = contextPropagators;
    this.propagatedContexts = propagatedContexts;
  }

  @Override
  public int getPriority() {
    return priority;
  }

  @Override
  public boolean runUntilBlocked(long deadlockDetectionTimeoutMs) {
    if (done) {
      return false;
    }
    done = true;
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    Map<String, Object> previousContexts = new HashMap<>();
    DeterministicRunnerImpl.setCurrentThreadInternal(owner);
    executing.set(true);
    try {
      for (ContextPropagator propagator : contextPropagators) {
        previousContexts.put(propagator.getName(), propagator.getCurrentContext());
        if (propagatedContexts.containsKey(propagator.getName())) {
          propagator.setCurrentContext(propagatedContexts.get(propagator.getName()));
        }
      }
      MDC.put(LoggerTag.WORKFLOW_ID, owner.getWorkflowContext().getReplayContext().getWorkflowId());
      MDC.put(
          LoggerTag.WORKFLOW_TYPE,
          owner.getWorkflowContext().getReplayContext().getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, owner.getWorkflowContext().getReplayContext().getRunId());
      MDC.put(LoggerTag.TASK_QUEUE, owner.getWorkflowContext().getReplayContext().getTaskQueue());
      MDC.put(LoggerTag.NAMESPACE, owner.getWorkflowContext().getReplayContext().getNamespace());
      scope.run();
    } finally {
      try {
        for (ContextPropagator propagator : contextPropagators) {
          if (previousContexts.containsKey(propagator.getName())) {
            propagator.setCurrentContext(previousContexts.get(propagator.getName()));
          }
        }
      } finally {
        if (previousMdc == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(previousMdc);
        }
        executing.remove();
        DeterministicRunnerImpl.setCurrentThreadInternal(null);
      }
    }
    return true;
  }

  @Override
  public boolean isDone() {
    return done;
  }

  @Override
  public Throwable getUnhandledException() {
    return null;
  }

  @Override
  public Future<?> stopNow() {
    done = true;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void addStackTrace(StringBuilder result) {
    result.append("async Temporal operation");
  }
}
