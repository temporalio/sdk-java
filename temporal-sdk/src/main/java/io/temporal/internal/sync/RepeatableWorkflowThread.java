package io.temporal.internal.sync;

import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.worker.WorkflowExecutorCache;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Implementation of WorkflowThread that repeatedly evaluates a read-only condition on each
 * runUntilBlocked() call until the condition returns true.
 *
 * <p>IMPORTANT: The condition must be read-only - it should only observe workflow state, not modify
 * it. This is because the condition may be evaluated multiple times per workflow task, and
 * modifying state would cause non-determinism. Conditions should be simple boolean expressions that
 * check workflow variables or promise states.
 *
 * <p>Key behavior:
 *
 * <ul>
 *   <li>Each condition evaluation runs in its own WorkflowThreadImpl for proper workflow context
 *   <li>Reports progress (returns true) ONLY when condition becomes true or throws an exception
 *   <li>When condition returns false, reports no progress - acts as "yielded" waiting for state
 *       change
 *   <li>This prevents infinite loops in the event loop when conditions remain false
 * </ul>
 *
 * <p>The thread completes when:
 *
 * <ul>
 *   <li>The condition returns true
 *   <li>The condition throws an exception
 *   <li>The thread is cancelled
 *   <li>The thread is destroyed (stopNow)
 * </ul>
 */
class RepeatableWorkflowThread implements WorkflowThread {

  /**
   * Flag indicating that the user's condition became true. Once true, the thread is considered
   * done.
   */
  private volatile boolean conditionSatisfied = false;

  /** Flag indicating the thread has been cancelled. */
  private volatile boolean cancelRequested = false;

  /** Cancellation reason if cancelled. */
  private volatile String cancellationReason;

  /** Exception from the internal thread that needs to be propagated. */
  private volatile Throwable propagatedException;

  /** The current internal thread executing the condition. */
  private WorkflowThreadImpl currentEvaluationThread;

  /** Counter for naming internal threads. */
  private int evaluationCount = 0;

  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final SyncWorkflowContext syncWorkflowContext;
  private final DeterministicRunnerImpl runner;
  private final String threadName;
  private final int priority;
  private final boolean detached;
  private final CancellationScopeImpl parentCancellationScope;
  private final Supplier<Boolean> condition;
  private final WorkflowExecutorCache cache;
  private final List<ContextPropagator> contextPropagators;
  private final Map<String, Object> propagatedContexts;
  private final Map<WorkflowThreadLocalInternal<?>, Object> threadLocalMap = new HashMap<>();

  RepeatableWorkflowThread(
      WorkflowThreadExecutor workflowThreadExecutor,
      SyncWorkflowContext syncWorkflowContext,
      DeterministicRunnerImpl runner,
      @Nonnull String name,
      int priority,
      boolean detached,
      CancellationScopeImpl parentCancellationScope,
      Supplier<Boolean> condition,
      WorkflowExecutorCache cache,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    this.workflowThreadExecutor = workflowThreadExecutor;
    this.syncWorkflowContext = syncWorkflowContext;
    this.runner = runner;
    this.threadName =
        com.google.common.base.Preconditions.checkNotNull(name, "Thread name shouldn't be null");
    this.priority = priority;
    this.detached = detached;
    this.parentCancellationScope = parentCancellationScope;
    this.condition = condition;
    this.cache = cache;
    this.contextPropagators = contextPropagators;
    this.propagatedContexts = propagatedContexts;
  }

  /**
   * Creates a new internal WorkflowThreadImpl for evaluating the condition. The thread's runnable
   * evaluates the condition once and sets conditionSatisfied if true.
   */
  private WorkflowThreadImpl createEvaluationThread() {
    evaluationCount++;
    String evalThreadName = threadName + "-eval-" + evaluationCount;

    Runnable evaluationRunnable =
        () -> {
          // Check cancellation at start of evaluation
          // Check both our flag and the parent scope (which gets cancelled by the runner)
          if (isCancelled()) {
            throw new io.temporal.failure.CanceledFailure(getEffectiveCancellationReason());
          }

          // Evaluate the condition - this may yield if condition calls await, activity, etc.
          boolean result = condition.get();

          // Check cancellation after evaluation (in case it was requested during)
          if (isCancelled()) {
            throw new io.temporal.failure.CanceledFailure(getEffectiveCancellationReason());
          }

          if (result) {
            conditionSatisfied = true;
          }
        };

    return new WorkflowThreadImpl(
        workflowThreadExecutor,
        syncWorkflowContext,
        runner,
        evalThreadName,
        priority,
        detached,
        parentCancellationScope,
        evaluationRunnable,
        cache,
        contextPropagators,
        propagatedContexts);
  }

  @Override
  public boolean runUntilBlocked(long deadlockDetectionTimeoutMs) {
    // Already done - no more work
    if (isDone()) {
      return false;
    }

    // If no current thread, or current thread completed, create a new one
    if (currentEvaluationThread == null || currentEvaluationThread.isDone()) {
      if (conditionSatisfied) {
        return false; // Already done
      }
      currentEvaluationThread = createEvaluationThread();
    }

    // Run the internal thread
    currentEvaluationThread.runUntilBlocked(deadlockDetectionTimeoutMs);

    // Check for unhandled exception from the internal thread
    Throwable unhandledException = currentEvaluationThread.getUnhandledException();
    if (unhandledException != null) {
      // Store exception for the runner to pick up via getUnhandledException()
      propagatedException = unhandledException;
      // Return true to signal progress so the runner checks isDone() and finds the exception
      return true;
    }

    // Return true ONLY when condition is satisfied.
    // When condition returns false, we report no progress - this thread acts as "yielded",
    // waiting for other threads to change state. This prevents the event loop from
    // spinning indefinitely when conditions remain false.
    return conditionSatisfied;
  }

  @Override
  public boolean isDone() {
    // Done when condition is satisfied
    if (conditionSatisfied) {
      return true;
    }
    // Done if there's an exception to propagate
    if (propagatedException != null) {
      return true;
    }
    // Also done if cancelled (either directly or via parent scope) and current thread is done
    if (isCancelled() && (currentEvaluationThread == null || currentEvaluationThread.isDone())) {
      return true;
    }
    return false;
  }

  @Override
  public Throwable getUnhandledException() {
    // Return the propagated exception if we have one
    if (propagatedException != null) {
      return propagatedException;
    }
    // Otherwise check the current evaluation thread
    if (currentEvaluationThread != null) {
      return currentEvaluationThread.getUnhandledException();
    }
    return null;
  }

  @Override
  public void cancel() {
    cancel(null);
  }

  @Override
  public void cancel(String reason) {
    cancelRequested = true;
    cancellationReason = reason;
    // Also cancel the current evaluation thread if it exists
    if (currentEvaluationThread != null) {
      currentEvaluationThread.cancel(reason);
    }
  }

  @Override
  public boolean isCancelRequested() {
    return isCancelled();
  }

  /**
   * Checks if this thread should be considered cancelled. This includes both explicit cancellation
   * via cancel() and cancellation propagated through the parent CancellationScope.
   */
  private boolean isCancelled() {
    return cancelRequested
        || (parentCancellationScope != null && parentCancellationScope.isCancelRequested());
  }

  /** Gets the cancellation reason, checking parent scope if needed. */
  private String getEffectiveCancellationReason() {
    if (cancellationReason != null) {
      return cancellationReason;
    }
    if (parentCancellationScope != null && parentCancellationScope.isCancelRequested()) {
      return parentCancellationScope.getCancellationReason();
    }
    return null;
  }

  @Override
  public String getCancellationReason() {
    return getEffectiveCancellationReason();
  }

  @Override
  public io.temporal.workflow.Promise<String> getCancellationRequest() {
    if (currentEvaluationThread != null) {
      return currentEvaluationThread.getCancellationRequest();
    }
    // Return a promise that completes if cancelled
    io.temporal.workflow.CompletablePromise<String> promise =
        io.temporal.workflow.Workflow.newPromise();
    if (cancelRequested) {
      promise.complete(cancellationReason);
    }
    return promise;
  }

  @Override
  public boolean isDetached() {
    return detached;
  }

  @Override
  public Future<?> stopNow() {
    cancelRequested = true;
    if (currentEvaluationThread != null) {
      return currentEvaluationThread.stopNow();
    }
    return java.util.concurrent.CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isStarted() {
    return true; // We start on first runUntilBlocked
  }

  @Override
  public void start() {
    // No-op - we start on first runUntilBlocked()
  }

  @Override
  public String getName() {
    return threadName;
  }

  @Override
  public void setName(String name) {
    // Thread name is immutable for RepeatableWorkflowThread
  }

  @Override
  public long getId() {
    return hashCode();
  }

  @Override
  public int getPriority() {
    return priority;
  }

  @Override
  public void run() {
    throw new UnsupportedOperationException("not used");
  }

  @Override
  public String getStackTrace() {
    if (currentEvaluationThread != null) {
      return currentEvaluationThread.getStackTrace();
    }
    return threadName + "\n\t(not running)";
  }

  @Override
  public void addStackTrace(StringBuilder result) {
    result.append(threadName);
    if (currentEvaluationThread != null) {
      result.append(": delegating to ");
      currentEvaluationThread.addStackTrace(result);
    } else {
      result.append("(NOT RUNNING)");
    }
  }

  @Override
  public void yield(String reason, Supplier<Boolean> unblockCondition) {
    if (currentEvaluationThread != null) {
      currentEvaluationThread.yield(reason, unblockCondition);
    } else {
      throw new IllegalStateException("Cannot yield - no evaluation thread running");
    }
  }

  @Override
  public void exitThread() {
    if (currentEvaluationThread != null) {
      currentEvaluationThread.exitThread();
    }
  }

  @Override
  public DeterministicRunnerImpl getRunner() {
    return runner;
  }

  @Override
  public SyncWorkflowContext getWorkflowContext() {
    return syncWorkflowContext;
  }

  @Override
  public WorkflowThreadContext getWorkflowThreadContext() {
    if (currentEvaluationThread != null) {
      return currentEvaluationThread.getWorkflowThreadContext();
    }
    return null;
  }

  @Override
  public io.temporal.internal.common.NonIdempotentHandle lockDeadlockDetector() {
    if (currentEvaluationThread != null) {
      return currentEvaluationThread.lockDeadlockDetector();
    }
    return null;
  }

  @Override
  public <T> void setThreadLocal(WorkflowThreadLocalInternal<T> key, T value) {
    threadLocalMap.put(key, value);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<Optional<T>> getThreadLocal(WorkflowThreadLocalInternal<T> key) {
    if (!threadLocalMap.containsKey(key)) {
      return Optional.empty();
    }
    return Optional.of(Optional.ofNullable((T) threadLocalMap.get(key)));
  }
}
