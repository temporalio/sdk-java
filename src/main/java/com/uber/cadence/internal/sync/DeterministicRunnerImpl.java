/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.internal.sync;

import com.uber.cadence.ChildPolicy;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.common.CheckedExceptionWrapper;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.cadence.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.internal.replay.DecisionContext;
import com.uber.cadence.internal.replay.ExecuteActivityParameters;
import com.uber.cadence.internal.replay.ExecuteLocalActivityParameters;
import com.uber.cadence.internal.replay.SignalExternalWorkflowParameters;
import com.uber.cadence.internal.replay.StartChildWorkflowExecutionParameters;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Functions.Func1;
import com.uber.cadence.workflow.Promise;
import com.uber.m3.tally.Scope;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Throws Error in case of any unexpected condition. It is to fail a decision, not a workflow. */
class DeterministicRunnerImpl implements DeterministicRunner {

  private static class NamedRunnable {
    private final String name;
    private final Runnable runnable;

    private NamedRunnable(String name, Runnable runnable) {
      this.name = name;
      this.runnable = runnable;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(DeterministicRunnerImpl.class);
  static final String WORKFLOW_ROOT_THREAD_NAME = "workflow-root";
  private static final ThreadLocal<WorkflowThread> currentThreadThreadLocal = new ThreadLocal<>();

  private final Lock lock = new ReentrantLock();
  private final ExecutorService threadPool;
  private final SyncDecisionContext decisionContext;
  private final Deque<WorkflowThread> threads = new ArrayDeque<>(); // protected by lock
  // Values from RunnerLocalInternal
  private final Map<RunnerLocalInternal<?>, Object> runnerLocalMap = new HashMap<>();

  private final List<WorkflowThread> threadsToAdd = Collections.synchronizedList(new ArrayList<>());
  private final List<NamedRunnable> toExecuteInWorkflowThread = new ArrayList<>();
  private final Supplier<Long> clock;
  private DeciderCache cache;
  private boolean inRunUntilAllBlocked;
  private boolean closeRequested;
  private boolean closed;

  static WorkflowThread currentThreadInternal() {
    WorkflowThread result = currentThreadThreadLocal.get();
    if (result == null) {
      throw new Error("Called from non workflow or workflow callback thread");
    }
    return result;
  }

  static void setCurrentThreadInternal(WorkflowThread coroutine) {
    currentThreadThreadLocal.set(coroutine);
  }

  /**
   * Time at which any thread that runs under sync can make progress. For example when {@link
   * com.uber.cadence.workflow.Workflow#sleep(long)} expires. 0 means no blocked threads.
   */
  private long nextWakeUpTime;
  /**
   * Used to check for failedPromises that contain an error, but never where accessed. It is to
   * avoid failure swallowing by failedPromises which is very hard to troubleshoot.
   */
  private Set<Promise> failedPromises = new HashSet<>();

  private boolean exitRequested;
  private Object exitValue;
  private WorkflowThread rootWorkflowThread;
  private final CancellationScopeImpl runnerCancellationScope;

  DeterministicRunnerImpl(Runnable root) {
    this(System::currentTimeMillis, root);
  }

  DeterministicRunnerImpl(Supplier<Long> clock, Runnable root) {
    this(getDefaultThreadPool(), newDummySyncDecisionContext(), clock, root, null);
  }

  private static ThreadPoolExecutor getDefaultThreadPool() {
    ThreadPoolExecutor result =
        new ThreadPoolExecutor(0, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    result.setThreadFactory(
        new ThreadFactory() {
          @Override
          public Thread newThread(Runnable r) {
            return new Thread(r, "deterministic runner thread");
          }
        });
    return result;
  }

  DeterministicRunnerImpl(
      ExecutorService threadPool,
      SyncDecisionContext decisionContext,
      Supplier<Long> clock,
      Runnable root) {
    this(threadPool, decisionContext, clock, root, null);
  }

  DeterministicRunnerImpl(
      ExecutorService threadPool,
      SyncDecisionContext decisionContext,
      Supplier<Long> clock,
      Runnable root,
      DeciderCache cache) {
    this.threadPool = threadPool;
    this.decisionContext =
        decisionContext != null ? decisionContext : newDummySyncDecisionContext();
    this.clock = clock;
    this.cache = cache;
    runnerCancellationScope = new CancellationScopeImpl(true, null, null);
    // TODO: workflow instance specific thread name
    rootWorkflowThread =
        new WorkflowThreadImpl(
            true,
            threadPool,
            this,
            WORKFLOW_ROOT_THREAD_NAME,
            false,
            runnerCancellationScope,
            root,
            cache);
    threads.addLast(rootWorkflowThread);
    rootWorkflowThread.start();
  }

  private static SyncDecisionContext newDummySyncDecisionContext() {
    return new SyncDecisionContext(
        new DummyDecisionContext(), JsonDataConverter.getInstance(), (next) -> next, null);
  }

  SyncDecisionContext getDecisionContext() {
    return decisionContext;
  }

  @Override
  public void runUntilAllBlocked() throws Throwable {
    lock.lock();
    try {
      checkClosed();

      inRunUntilAllBlocked = true;
      Throwable unhandledException = null;
      // Keep repeating until at least one of the threads makes progress.
      boolean progress;
      outerLoop:
      do {
        threadsToAdd.clear();

        if (!toExecuteInWorkflowThread.isEmpty()) {
          List<WorkflowThread> callbackThreads = new ArrayList<>(toExecuteInWorkflowThread.size());
          for (NamedRunnable nr : toExecuteInWorkflowThread) {
            WorkflowThread thread =
                new WorkflowThreadImpl(
                    false,
                    threadPool,
                    this,
                    nr.name,
                    false,
                    runnerCancellationScope,
                    nr.runnable,
                    cache);
            callbackThreads.add(thread);
          }

          // It is important to prepend threads as there are callbacks
          // like signals that have to run before any other threads.
          // Otherwise signal might be never processed if it was received
          // after workflow decided to close.
          // Adding the callbacks in the same order as they appear in history.

          for (int i = callbackThreads.size() - 1; i >= 0; i--) {
            threads.addFirst(callbackThreads.get(i));
          }
        }

        toExecuteInWorkflowThread.clear();
        progress = false;
        Iterator<WorkflowThread> ci = threads.iterator();
        nextWakeUpTime = 0;
        while (ci.hasNext()) {
          WorkflowThread c = ci.next();
          progress = c.runUntilBlocked() || progress;
          if (exitRequested) {
            close();
            break outerLoop;
          }
          if (c.isDone()) {
            ci.remove();
            if (c.getUnhandledException() != null) {
              unhandledException = c.getUnhandledException();
              break;
            }
          } else {
            long t = c.getBlockedUntil();
            if (t > nextWakeUpTime) {
              nextWakeUpTime = t;
            }
          }
        }
        if (unhandledException != null) {
          close();
          throw unhandledException;
        }
        for (WorkflowThread c : threadsToAdd) {
          threads.addLast(c);
        }
      } while (progress && !threads.isEmpty());
      if (nextWakeUpTime < currentTimeMillis()) {
        nextWakeUpTime = 0;
      }
    } finally {
      inRunUntilAllBlocked = false;
      // Close was requested while running
      if (closeRequested) {
        close();
      }
      lock.unlock();
    }
  }

  @Override
  public boolean isDone() {
    lock.lock();
    try {
      return closed || threads.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object getExitValue() {
    lock.lock();
    try {
      if (!closed) {
        throw new Error("not done");
      }
    } finally {
      lock.unlock();
    }
    return exitValue;
  }

  @Override
  public void cancel(String reason) {
    executeInWorkflowThread("cancel workflow callback", () -> rootWorkflowThread.cancel(reason));
  }

  @Override
  public void close() {
    List<Future<?>> threadFutures = new ArrayList<>();
    lock.lock();
    if (closed) {
      lock.unlock();
      return;
    }
    // Do not close while runUntilAllBlocked executes.
    // closeRequested tells it to call close() at the end.
    closeRequested = true;
    if (inRunUntilAllBlocked) {
      lock.unlock();
      return;
    }
    try {
      for (WorkflowThread c : threadsToAdd) {
        threads.addLast(c);
      }
      threadsToAdd.clear();

      for (WorkflowThread c : threads) {
        threadFutures.add(c.stopNow());
      }
      threads.clear();

      // We cannot use an iterator to unregister failed Promises since f.get()
      // will remove the promise directly from failedPromises. This causes an
      // ConcurrentModificationException
      // For this reason we will loop over a copy of failedPromises.
      Set<Promise> failedPromisesLoop = new HashSet<>(failedPromises);
      for (Promise f : failedPromisesLoop) {
        if (!f.isCompleted()) {
          throw new Error("expected failed");
        }
        try {
          f.get();
          throw new Error("unreachable");
        } catch (RuntimeException e) {
          log.warn(
              "Promise that was completedExceptionally was never accessed. "
                  + "The ignored exception:",
              CheckedExceptionWrapper.unwrap(e));
        }
      }
    } finally {
      closed = true;
      lock.unlock();
    }

    // Context is destroyed in c.StopNow(). Wait on all tasks outside the lock since
    // these tasks use the same lock to execute.
    for (Future<?> future : threadFutures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        throw new Error("Unexpected interrupt", e);
      } catch (ExecutionException e) {
        throw new Error("Unexpected failure stopping coroutine", e);
      }
    }
  }

  @Override
  public String stackTrace() {
    StringBuilder result = new StringBuilder();
    lock.lock();
    try {
      checkClosed();
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

  private void checkClosed() {
    if (closed) {
      throw new Error("closed");
    }
  }

  @Override
  public long currentTimeMillis() {
    return clock.get();
  }

  @Override
  public long getNextWakeUpTime() {
    lock.lock();
    try {
      checkClosed();
      if (decisionContext != null) {
        long nextFireTime = decisionContext.getNextFireTime();
        if (nextWakeUpTime == 0) {
          return nextFireTime;
        }
        if (nextFireTime == 0) {
          return nextWakeUpTime;
        }
        return Math.min(nextWakeUpTime, nextFireTime);
      }
      return nextWakeUpTime;
    } finally {
      lock.unlock();
    }
  }

  WorkflowThread newThread(Runnable runnable, boolean detached, String name) {
    checkWorkflowThreadOnly();
    checkClosed();
    WorkflowThread result =
        new WorkflowThreadImpl(
            false,
            threadPool,
            this,
            name,
            detached,
            CancellationScopeImpl.current(),
            runnable,
            cache);
    threadsToAdd.add(result); // This is synchronized collection.
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
      checkClosed();
      toExecuteInWorkflowThread.add(new NamedRunnable(name, runnable));
    } finally {
      lock.unlock();
    }
  }

  Lock getLock() {
    return lock;
  }

  /** Register a promise that had failed but wasn't accessed yet. */
  void registerFailedPromise(Promise promise) {
    failedPromises.add(promise);
  }

  /** Forget a failed promise as it was accessed. */
  void forgetFailedPromise(Promise promise) {
    failedPromises.remove(promise);
  }

  <R> void exit(R value) {
    checkClosed();
    checkWorkflowThreadOnly();
    this.exitValue = value;
    this.exitRequested = true;
  }

  private void checkWorkflowThreadOnly() {
    if (!inRunUntilAllBlocked) {
      throw new Error("called from non workflow thread");
    }
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

  private static final class DummyDecisionContext implements DecisionContext {

    @Override
    public WorkflowExecution getWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowType getWorkflowType() {
      return new WorkflowType().setName("dummy-workflow");
    }

    @Override
    public boolean isCancelRequested() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void setContinueAsNewOnCompletion(
        ContinueAsNewWorkflowExecutionParameters continueParameters) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public int getExecutionStartToCloseTimeoutSeconds() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public String getTaskList() {
      return "dummy-task-list";
    }

    @Override
    public String getDomain() {
      return "dummy-domain";
    }

    @Override
    public String getWorkflowId() {
      return "dummy-workflow-id";
    }

    @Override
    public String getRunId() {
      return "dummy-run-id";
    }

    @Override
    public Duration getExecutionStartToCloseTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Duration getDecisionTaskTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public ChildPolicy getChildPolicy() {
      return ChildPolicy.TERMINATE;
    }

    @Override
    public Consumer<Exception> scheduleActivityTask(
        ExecuteActivityParameters parameters, BiConsumer<byte[], Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Consumer<Exception> scheduleLocalActivityTask(
        ExecuteLocalActivityParameters parameters, BiConsumer<byte[], Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Consumer<Exception> startChildWorkflow(
        StartChildWorkflowExecutionParameters parameters,
        Consumer<WorkflowExecution> executionCallback,
        BiConsumer<byte[], Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean isServerSideChildWorkflowRetry() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean isServerSideActivityRetry() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Consumer<Exception> signalWorkflowExecution(
        SignalExternalWorkflowParameters signalParameters, BiConsumer<Void, Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Promise<Void> requestCancelWorkflowExecution(WorkflowExecution execution) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Optional<byte[]> mutableSideEffect(
        String id, DataConverter converter, Func1<Optional<byte[]>, Optional<byte[]>> func) {
      return func.apply(Optional.empty());
    }

    @Override
    public long currentTimeMillis() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean isReplaying() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Consumer<Exception> createTimer(long delaySeconds, Consumer<Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public byte[] sideEffect(Func<byte[]> func) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public int getVersion(
        String changeID, DataConverter converter, int minSupported, int maxSupported) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Random newRandom() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Scope getMetricsScope() {
      return NoopScope.getInstance();
    }

    @Override
    public boolean getEnableLoggingInReplay() {
      return false;
    }

    @Override
    public UUID randomUUID() {
      return UUID.randomUUID();
    }
  }
}
