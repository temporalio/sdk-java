/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.context.ContextThreadLocal;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.workflow.Promise;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throws Error in case of any unexpected condition. It is to fail a workflow task, not a workflow.
 */
class DeterministicRunnerImpl implements DeterministicRunner {

  private static final int ROOT_THREAD_PRIORITY = 0;
  private static final int CALLBACK_THREAD_PRIORITY = 10;
  private static final int WORKFLOW_THREAD_PRIORITY = 20000000;

  static final String WORKFLOW_ROOT_THREAD_NAME = "workflow-root";

  private static final Logger log = LoggerFactory.getLogger(DeterministicRunnerImpl.class);
  private static final ThreadLocal<WorkflowThread> currentThreadThreadLocal = new ThreadLocal<>();

  // Runner Lock is taken shortly by Workflow Threads when they resume and yield.
  // It's taken and retained also by DeterministicRunnerImpl control code.
  // This ensures happens-before between all workflow threads and DeterministicRunnerImpl control
  // code.
  // Note that the Runner Lock is not retained when workflow thread executes and not inside yield
  // method.
  // To check for an active event loop, inRunUntilAllBlocked value taken under Runner Lock should be
  // used.
  private final Lock lock = new ReentrantLock();
  // true when the control code of the main workflow event loop is running.
  // Workflow methods may get unblocked and executed by the control code when true.
  // Updated always with Runner Lock taken.
  private boolean inRunUntilAllBlocked;

  // Note that threads field is a set. So we need to make sure that getPriority never returns the
  // same value for different threads. We use addedThreads variable for this. Protected by lock
  private final Set<WorkflowThread> threads =
      new TreeSet<>((t1, t2) -> Ints.compare(t1.getPriority(), t2.getPriority()));
  // Values from RunnerLocalInternal
  private final Map<RunnerLocalInternal<?>, Object> runnerLocalMap = new HashMap<>();
  private final Runnable rootRunnable;
  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final SyncWorkflowContext workflowContext;
  private final WorkflowExecutorCache cache;

  // always accessed under the runner lock
  private final List<NamedRunnable> toExecuteInWorkflowThread = new ArrayList<>();

  // Access to workflowThreadsToAdd, callbackThreadsToAdd, addedThreads doesn't have to be
  // synchronized.
  // Inside DeterministicRunner the access to these variables is under the runner lock.
  //
  // newWorkflowThread can be called from the workflow thread directly by using an Async.
  // But Workflow Threads when resuming or yielding are required to get the same runner lock as the
  // DeterministicRunner itself, which provides happens-before and guarantees visibility of changes
  // to these collections.
  // Only one Workflow Thread can run at a time and no DeterministicRunner code modifying these
  // variables run at the same time with the Workflow Thread.
  private final List<WorkflowThread> workflowThreadsToAdd = new ArrayList<>();
  private final List<WorkflowThread> callbackThreadsToAdd = new ArrayList<>();
  private int addedThreads;

  /**
   * Close is requested by the workflow code and the workflow thread itself. Such close is processed
   * immediately after the requesting thread is blocked, other workflow threads don't get a chance
   * to proceed after it.
   */
  private boolean exitRequested;

  /**
   * Close is requested by the control code. This close is potentially delayed and wait till the
   * workflow code is blocked if it's currently processing.
   */
  private boolean closeRequested;

  /**
   * true if some thread already started performing closure. Only one thread can do it and only
   * once.
   */
  private boolean closeStarted;

  /** If this future is filled, the runner is successfully closed */
  private final CompletableFuture<?> closeFuture = new CompletableFuture<>();

  static WorkflowThread currentThreadInternal() {
    WorkflowThread result = currentThreadThreadLocal.get();
    if (result == null) {
      throw new Error("Called from non workflow or workflow callback thread");
    }
    return result;
  }

  static Optional<WorkflowThread> currentThreadInternalIfPresent() {
    WorkflowThread result = currentThreadThreadLocal.get();
    if (result == null) {
      return Optional.empty();
    }
    return Optional.of(result);
  }

  static void setCurrentThreadInternal(WorkflowThread coroutine) {
    if (coroutine != null) {
      currentThreadThreadLocal.set(coroutine);
      WorkflowThreadMarkerAccessor.markAsWorkflowThread();
    } else {
      currentThreadThreadLocal.set(null);
      WorkflowThreadMarkerAccessor.markAsNonWorkflowThread();
    }
  }

  /**
   * Used to check for failedPromises that contain an error, but never where accessed. It is to
   * avoid failure swallowing by failedPromises which is very hard to troubleshoot.
   */
  private final Set<Promise<?>> failedPromises = new HashSet<>();

  private WorkflowThread rootWorkflowThread;
  private final CancellationScopeImpl runnerCancellationScope;

  DeterministicRunnerImpl(
      WorkflowThreadExecutor workflowThreadExecutor,
      @Nonnull SyncWorkflowContext workflowContext,
      Runnable root) {
    this(workflowThreadExecutor, workflowContext, root, null);
  }

  DeterministicRunnerImpl(
      WorkflowThreadExecutor workflowThreadExecutor,
      @Nonnull SyncWorkflowContext workflowContext,
      Runnable root,
      WorkflowExecutorCache cache) {
    this.workflowThreadExecutor = workflowThreadExecutor;
    this.workflowContext = Preconditions.checkNotNull(workflowContext, "workflowContext");
    this.workflowContext.setRunner(this);
    this.cache = cache;
    this.runnerCancellationScope = new CancellationScopeImpl(true, null, null);
    this.rootRunnable = root;
  }

  SyncWorkflowContext getWorkflowContext() {
    return workflowContext;
  }

  @Override
  public void runUntilAllBlocked(long deadlockDetectionTimeout) {
    if (rootWorkflowThread == null) {
      rootWorkflowThread = newRootThread(rootRunnable);
      threads.add(rootWorkflowThread);
      rootWorkflowThread.start();
    }
    lock.lock();
    try {
      checkNotClosed();
      checkNotCloseRequestedLocked();
      inRunUntilAllBlocked = true;
      // Keep repeating until at least one of the threads makes progress.
      boolean progress;
      outerLoop:
      do {
        if (exitRequested) {
          closeRequested = true;
          break;
        }
        if (!toExecuteInWorkflowThread.isEmpty()) {
          for (NamedRunnable nr : toExecuteInWorkflowThread) {
            Object callbackThread =
                workflowContext
                    .getWorkflowInboundInterceptor()
                    .newCallbackThread(nr.runnable, nr.name);
            Preconditions.checkState(
                callbackThread != null,
                "[BUG] One of the custom interceptors illegally overrode newCallbackThread result to null. "
                    + "Check WorkflowInboundCallsInterceptor#newCallbackThread contract.");
            Preconditions.checkState(
                callbackThread instanceof WorkflowThread,
                "[BUG] One of the custom interceptors illegally overrode newCallbackThread result. "
                    + "Check WorkflowInboundCallsInterceptor#newCallbackThread contract. "
                    + "Illegal object returned from the interceptors chain: "
                    + callbackThread);
          }

          appendCallbackThreadsLocked();
        }
        toExecuteInWorkflowThread.clear();
        progress = false;
        Iterator<WorkflowThread> ci = threads.iterator();
        while (ci.hasNext()) {
          WorkflowThread c = ci.next();
          progress = c.runUntilBlocked(deadlockDetectionTimeout) || progress;
          if (exitRequested) {
            closeRequested = true;
            break outerLoop;
          }
          if (c.isDone()) {
            ci.remove();
            Throwable unhandledException = c.getUnhandledException();
            if (unhandledException != null) {
              closeRequested = true;
              throw WorkflowInternal.wrap(unhandledException);
            }
          }
        }
        appendWorkflowThreadsLocked();
      } while (progress && !threads.isEmpty());
    } catch (PotentialDeadlockException e) {
      String triggerThreadStackTrace = "";
      StringBuilder otherThreadsDump = new StringBuilder();
      for (WorkflowThread t : threads) {
        if (t.getWorkflowThreadContext() != e.getWorkflowThreadContext()) {
          if (otherThreadsDump.length() > 0) {
            otherThreadsDump.append("\n");
          }
          otherThreadsDump.append(t.getStackTrace());
        } else {
          triggerThreadStackTrace = t.getStackTrace();
        }
      }
      e.setStackDump(
          triggerThreadStackTrace, otherThreadsDump.toString(), System.currentTimeMillis());
      throw e;
    } finally {
      inRunUntilAllBlocked = false;
      lock.unlock();
      // Close was requested while running
      if (closeRequested) {
        close();
      }
    }
  }

  @Override
  public boolean isDone() {
    lock.lock();
    try {
      return closeFuture.isDone()
          // if close is requested, we should wait for the closeFuture to be filled
          || !closeRequested && !areThreadsToBeExecuted();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void cancel(String reason) {
    executeInWorkflowThread("cancel workflow callback", () -> rootWorkflowThread.cancel(reason));
  }

  /**
   * Destroys all controlled workflow threads by throwing {@link DestroyWorkflowThreadError} from
   * {@link WorkflowThreadContext#yield(String, Supplier)} when the threads are blocking on the
   * temporal-sdk code.
   */
  @Override
  public void close() {
    lock.lock();
    if (closeFuture.isDone()) {
      lock.unlock();
      return;
    }
    closeRequested = true;
    if (
    // If runUntilAllBlocked is true, event loop and workflow threads are executing.
    // Do not close immediately in this case.
    // closeRequested set earlier will make an event loop control thread to call close()
    // at the end that will actually perform the closure.
    inRunUntilAllBlocked
        // some other thread or caller is already in the process of closing
        || closeStarted) {

      lock.unlock();
      // We will not perform the closure in this call and should just wait on the future when
      // another thread responsible for it will close.
      closeFuture.join();
      return;
    }

    closeStarted = true;
    // lock is taken here
    try {
      // in some circumstances when a workflow broke Deadline Detector,
      // runUntilAllBlocked may return while workflow threads are still running.
      // If this happens, these threads may potentially start new additional threads that will be
      // in workflowThreadsToAdd and callbackThreadsToAdd.
      // That's why we need to make sure that all the spawned threads were shut down in a cycle.
      while (areThreadsToBeExecuted()) {
        List<WorkflowThreadStopFuture> threadFutures = new ArrayList<>();
        try {
          toExecuteInWorkflowThread.clear();
          appendWorkflowThreadsLocked();
          appendCallbackThreadsLocked();
          for (WorkflowThread workflowThread : threads) {
            threadFutures.add(
                new WorkflowThreadStopFuture(workflowThread, workflowThread.stopNow()));
          }
          threads.clear();

          // We cannot use an iterator to unregister failed Promises since f.get()
          // will remove the promise directly from failedPromises. This causes an
          // ConcurrentModificationException
          // For this reason we will loop over a copy of failedPromises.
          Set<Promise<?>> failedPromisesLoop = new HashSet<>(failedPromises);
          for (Promise<?> f : failedPromisesLoop) {
            try {
              f.get();
              throw new Error("unreachable");
            } catch (RuntimeException e) {
              log.warn(
                  "Promise completed with exception and was never accessed. The ignored exception:",
                  CheckedExceptionWrapper.unwrap(e));
            }
          }
        } finally {
          // we need to unlock for the further code because threads will not be able to proceed with
          // destruction otherwise.
          lock.unlock();
        }

        // Wait on all tasks outside the lock since these tasks use the same lock to execute.
        try {
          for (WorkflowThreadStopFuture threadFuture : threadFutures) {
            try {
              threadFuture.stopFuture.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
              WorkflowThread workflowThread = threadFuture.workflowThread;
              log.error(
                  "[BUG] Workflow thread '{}' of workflow '{}' can't be destroyed in time. "
                      + "This will lead to a workflow cache leak. "
                      + "This problem is usually caused by a workflow implementation swallowing java.lang.Error instead of rethrowing it. "
                      + " Thread dump of the stuck thread:\n{}",
                  workflowThread.getName(),
                  workflowContext.getContext().getWorkflowId(),
                  workflowThread.getStackTrace());
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          // Worker is likely stopped with shutdownNow()
          // TODO consider propagating as an original interrupted exception to the top level
          throw new Error("Worker executor thread interrupted during stopping of a coroutine", e);
        } catch (ExecutionException e) {
          throw new Error("[BUG] Unexpected failure while stopping a coroutine", e);
        } finally {
          // acquire the lock back as it should be taken for the loop condition check.
          lock.lock();
        }
      }
    } finally {
      closeFuture.complete(null);
      lock.unlock();
    }
  }

  @Override
  public String stackTrace() {
    StringBuilder result = new StringBuilder();
    lock.lock();
    try {
      if (closeFuture.isDone()) {
        return "Workflow is closed.";
      }
      for (WorkflowThread coroutine : threads) {
        if (result.length() > 0) {
          result.append("\n");
        }
        coroutine.addStackTrace(result);
      }
    } finally {
      lock.unlock();
    }
    return result.toString();
  }

  private void appendWorkflowThreadsLocked() {
    threads.addAll(workflowThreadsToAdd);
    workflowThreadsToAdd.clear();
  }

  private void appendCallbackThreadsLocked() {
    // TODO I'm not sure this comment makes sense, because threads list has comparator and we use
    // thread priorities anyway.

    // It is important to prepend threads as there are callbacks
    // like signals that have to run before any other threads.
    // Otherwise signal might be never processed if it was received
    // after workflow decided to close.
    // Adding the callbacks in the same order as they appear in history.
    for (int i = callbackThreadsToAdd.size() - 1; i >= 0; i--) {
      threads.add(callbackThreadsToAdd.get(i));
    }
    callbackThreadsToAdd.clear();
  }

  /** Creates a new instance of a root workflow thread. */
  private WorkflowThread newRootThread(Runnable runnable) {
    String name = WORKFLOW_ROOT_THREAD_NAME;
    // TODO: workflow instance specific thread name
    // String name = "workflow[" + workflowContext.getContext().getWorkflowId() + "]-root";
    if (rootWorkflowThread != null) {
      throw new IllegalStateException(
          "newRootThread can be called only if there is no existing root workflow thread");
    }
    rootWorkflowThread =
        new WorkflowThreadImpl(
            workflowThreadExecutor,
            this,
            name,
            ROOT_THREAD_PRIORITY,
            false,
            runnerCancellationScope,
            runnable,
            cache,
            getContextPropagators(),
            getPropagatedContexts());
    return rootWorkflowThread;
  }

  @Nonnull
  @Override
  public WorkflowThread newWorkflowThread(
      Runnable runnable, boolean detached, @Nullable String name) {
    if (name == null) {
      name = "workflow[" + workflowContext.getContext().getWorkflowId() + "]-" + addedThreads;
    }
    if (rootWorkflowThread == null) {
      throw new IllegalStateException(
          "newChildThread can be called only with existing root workflow thread");
    }
    checkWorkflowThreadOnly();
    checkNotClosed();
    WorkflowThread result =
        new WorkflowThreadImpl(
            workflowThreadExecutor,
            this,
            name,
            WORKFLOW_THREAD_PRIORITY + (addedThreads++),
            detached,
            CancellationScopeImpl.current(),
            runnable,
            cache,
            getContextPropagators(),
            getPropagatedContexts());
    workflowThreadsToAdd.add(result);
    return result;
  }

  @Nonnull
  @Override
  public WorkflowThread newCallbackThread(Runnable runnable, @Nullable String name) {
    if (name == null) {
      name = "workflow[" + workflowContext.getContext().getWorkflowId() + "]-" + addedThreads;
    }
    WorkflowThread result =
        new WorkflowThreadImpl(
            workflowThreadExecutor,
            this,
            name,
            CALLBACK_THREAD_PRIORITY
                + (addedThreads++), // maintain the order in toExecuteInWorkflowThread
            false,
            runnerCancellationScope,
            runnable,
            cache,
            getContextPropagators(),
            getPropagatedContexts());
    callbackThreadsToAdd.add(result);
    return result;
  }

  /**
   * Executes before any other threads next time runUntilBlockedCalled. Must never be called from
   * any workflow threads.
   */
  @Override
  public void executeInWorkflowThread(String name, Runnable runnable) {
    lock.lock();
    try {
      // if the execution is closed, we will just add the callbacks, but we will never create
      // threads for them, so they will be effectively ignored
      toExecuteInWorkflowThread.add(new NamedRunnable(name, runnable));
    } finally {
      lock.unlock();
    }
  }

  Lock getLock() {
    return lock;
  }

  /** Register a promise that had failed but wasn't accessed yet. */
  void registerFailedPromise(Promise<?> promise) {
    if (!promise.isCompleted()) {
      throw new Error("expected failed");
    }
    failedPromises.add(promise);
  }

  /** Forget a failed promise as it was accessed. */
  void forgetFailedPromise(Promise<?> promise) {
    failedPromises.remove(promise);
  }

  void exit() {
    checkNotClosed();
    checkWorkflowThreadOnly();
    this.exitRequested = true;
  }

  private void checkWorkflowThreadOnly() {
    // TODO this is not a correct way to test for the fact that the method is called from the
    // workflow method.
    //  This check verifies that the workflow methods or controlling code are now running,
    //  but it doesn't verify if the calling thread is the one.
    if (!inRunUntilAllBlocked) {
      throw new Error("called from non workflow thread");
    }
  }

  private void checkNotCloseRequestedLocked() {
    if (closeRequested) {
      throw new Error("close requested");
    }
  }

  private void checkNotClosed() {
    if (closeFuture.isDone()) {
      throw new Error("closed");
    }
  }

  /**
   * @return true if there are no threads left to be processed for this workflow.
   */
  private boolean areThreadsToBeExecuted() {
    return !threads.isEmpty()
        || !workflowThreadsToAdd.isEmpty()
        || !callbackThreadsToAdd.isEmpty()
        || !toExecuteInWorkflowThread.isEmpty();
  }

  @SuppressWarnings("unchecked")
  <T> Optional<T> getRunnerLocal(RunnerLocalInternal<T> key) {
    if (!runnerLocalMap.containsKey(key)) {
      return Optional.empty();
    }
    return Optional.of((T) runnerLocalMap.get(key));
  }

  <T> void setRunnerLocal(RunnerLocalInternal<T> key, T value) {
    runnerLocalMap.put(key, value);
  }

  /**
   * If we're executing as part of a workflow, get the current thread's context. Otherwise get the
   * context info from the workflow context.
   */
  private Map<String, Object> getPropagatedContexts() {
    if (currentThreadThreadLocal.get() != null) {
      return ContextThreadLocal.getCurrentContextForPropagation();
    } else {
      return workflowContext.getContext().getPropagatedContexts();
    }
  }

  private List<ContextPropagator> getContextPropagators() {
    if (currentThreadThreadLocal.get() != null) {
      return ContextThreadLocal.getContextPropagators();
    } else {
      return workflowContext.getContext().getContextPropagators();
    }
  }

  private static class WorkflowThreadMarkerAccessor extends WorkflowThreadMarker {
    public static void markAsWorkflowThread() {
      isWorkflowThreadThreadLocal.set(true);
    }

    public static void markAsNonWorkflowThread() {
      isWorkflowThreadThreadLocal.set(false);
    }
  }

  private static class NamedRunnable {
    private final String name;
    private final Runnable runnable;

    private NamedRunnable(String name, Runnable runnable) {
      this.name = name;
      this.runnable = runnable;
    }
  }

  private static class WorkflowThreadStopFuture {
    private final WorkflowThread workflowThread;
    private final Future<?> stopFuture;

    public WorkflowThreadStopFuture(WorkflowThread workflowThread, Future<?> stopFuture) {
      this.workflowThread = workflowThread;
      this.stopFuture = stopFuture;
    }
  }
}
