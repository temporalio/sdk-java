package io.temporal.internal.sync;

import static io.temporal.internal.sync.DeterministicRunnerImpl.currentThreadInternal;

import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.NonIdempotentHandle;
import io.temporal.workflow.CancellationScope;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Thread that is scheduled deterministically by {@link DeterministicRunner}. */
public interface WorkflowThread extends CancellationScope {

  /**
   * Block current thread until unblockCondition is evaluated to true. This method is intended for
   * framework level libraries, never use directly in a workflow implementation.
   *
   * @param reason reason for blocking
   * @param unblockCondition condition that should return true to indicate that thread should
   *     unblock.
   * @throws CanceledFailure if thread (or current cancellation scope was canceled).
   * @throws DestroyWorkflowThreadError if thread was asked to be destroyed.
   */
  static void await(String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    currentThreadInternal().yield(reason, unblockCondition);
  }

  /**
   * Creates a new thread instance.
   *
   * @param runnable thread function to run
   * @param detached If this thread is detached from the parent {@link CancellationScope}
   * @return
   */
  static WorkflowThread newThread(Runnable runnable, boolean detached) {
    return newThread(runnable, detached, null);
  }

  static WorkflowThread newThread(Runnable runnable, boolean detached, String name) {
    return (WorkflowThread)
        currentThreadInternal()
            .getWorkflowContext()
            .getWorkflowOutboundInterceptor()
            .newChildThread(runnable, detached, name);
  }

  void start();

  boolean isStarted();

  void setName(String name);

  String getName();

  long getId();

  int getPriority();

  String getStackTrace();

  DeterministicRunnerImpl getRunner();

  SyncWorkflowContext getWorkflowContext();

  /**
   * @param deadlockDetectionTimeoutMs maximum time in milliseconds the thread can run before
   *     calling yield.
   * @return true if coroutine made some progress.
   */
  boolean runUntilBlocked(long deadlockDetectionTimeoutMs);

  /**
   * Disables deadlock detector on this thread
   *
   * @return a handle that must be used to unlock the deadlock detector back
   */
  NonIdempotentHandle lockDeadlockDetector();

  Throwable getUnhandledException();

  boolean isDone();

  Future<?> stopNow();

  void addStackTrace(StringBuilder result);

  void yield(String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError;

  /**
   * Stop executing all workflow threads and puts {@link DeterministicRunner} into closed state. To
   * be called only from a workflow thread.
   */
  static void exit() {
    currentThreadInternal().exitThread(); // needed to close WorkflowThreadContext
  }

  void exitThread();

  <T> void setThreadLocal(WorkflowThreadLocalInternal<T> key, T value);

  <T> Optional<Optional<T>> getThreadLocal(WorkflowThreadLocalInternal<T> key);

  WorkflowThreadContext getWorkflowThreadContext();
}
