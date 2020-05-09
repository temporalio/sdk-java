/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.sync;

import com.google.common.primitives.Ints;
import com.uber.m3.tally.Scope;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GsonJsonDataConverter;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.context.ContextThreadLocal;
import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import io.temporal.internal.replay.DeciderCache;
import io.temporal.internal.replay.DecisionContext;
import io.temporal.internal.replay.ExecuteActivityParameters;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.internal.replay.SignalExternalWorkflowParameters;
import io.temporal.internal.replay.StartChildWorkflowExecutionParameters;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.execution.WorkflowExecution;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import io.temporal.workflow.Promise;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
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

  private static final int ROOT_THREAD_PRIORITY = 0;
  private static final int CALLBACK_THREAD_PRIORITY = 10;
  private static final int WORKFLOW_THREAD_PRIORITY = 20000000;

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

  // Note that threads field is a set. So we need to make sure that getPriority never returns the
  // same value for different threads. We use addedThreads variable for this.
  //
  // protected by lock
  private final Set<WorkflowThread> threads =
      new TreeSet<>((t1, t2) -> Ints.compare(t1.getPriority(), t2.getPriority()));
  // Values from RunnerLocalInternal
  private final Map<RunnerLocalInternal<?>, Object> runnerLocalMap = new HashMap<>();

  private final List<WorkflowThread> threadsToAdd = Collections.synchronizedList(new ArrayList<>());
  private int addedThreads;
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
   * io.temporal.workflow.Workflow#sleep(long)} expires. 0 means no blocked threads.
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
            ROOT_THREAD_PRIORITY,
            false,
            runnerCancellationScope,
            root,
            cache,
            getContextPropagators(),
            getPropagatedContexts());
    threads.add(rootWorkflowThread);
    rootWorkflowThread.start();
  }

  private static SyncDecisionContext newDummySyncDecisionContext() {
    return new SyncDecisionContext(
        new DummyDecisionContext(), GsonJsonDataConverter.getInstance(), null, null);
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
                    CALLBACK_THREAD_PRIORITY
                        + (addedThreads++), // maintain the order in toExecuteInWorkflowThread
                    false,
                    runnerCancellationScope,
                    nr.runnable,
                    cache,
                    getContextPropagators(),
                    getPropagatedContexts());
            callbackThreads.add(thread);
          }

          // It is important to prepend threads as there are callbacks
          // like signals that have to run before any other threads.
          // Otherwise signal might be never processed if it was received
          // after workflow decided to close.
          // Adding the callbacks in the same order as they appear in history.

          for (int i = callbackThreads.size() - 1; i >= 0; i--) {
            threads.add(callbackThreads.get(i));
          }
        }
        toExecuteInWorkflowThread.clear();
        progress = false;
        Iterator<WorkflowThread> ci = threads.iterator();
        nextWakeUpTime = Long.MAX_VALUE;
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
            if (t > currentTimeMillis() && t < nextWakeUpTime) {
              nextWakeUpTime = t;
            }
          }
        }
        if (unhandledException != null) {
          close();
          throw unhandledException;
        }
        for (WorkflowThread c : threadsToAdd) {
          threads.add(c);
        }
      } while (progress && !threads.isEmpty());

      if (nextWakeUpTime < currentTimeMillis() || nextWakeUpTime == Long.MAX_VALUE) {
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
        threads.add(c);
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

  /** To be called only from another workflow thread. */
  WorkflowThread newThread(Runnable runnable, boolean detached, String name) {
    checkWorkflowThreadOnly();
    checkClosed();
    WorkflowThread result =
        new WorkflowThreadImpl(
            false,
            threadPool,
            this,
            name,
            WORKFLOW_THREAD_PRIORITY + (addedThreads++),
            detached,
            CancellationScopeImpl.current(),
            runnable,
            cache,
            getContextPropagators(),
            getPropagatedContexts());
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

  /**
   * If we're executing as part of a workflow, get the current thread's context. Otherwise get the
   * context info from the DecisionContext
   */
  private Map<String, Object> getPropagatedContexts() {
    if (currentThreadThreadLocal.get() != null) {
      return ContextThreadLocal.getCurrentContextForPropagation();
    } else {
      return decisionContext.getContext().getPropagatedContexts();
    }
  }

  private List<ContextPropagator> getContextPropagators() {
    if (currentThreadThreadLocal.get() != null) {
      return ContextThreadLocal.getContextPropagators();
    } else {
      return decisionContext.getContext().getContextPropagators();
    }
  }

  private static final class DummyDecisionContext implements DecisionContext {

    @Override
    public WorkflowExecution getWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowExecution getParentWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowType getWorkflowType() {
      return WorkflowType.newBuilder().setName("dummy-workflow").build();
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
    public String getContinuedExecutionRunId() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public String getTaskList() {
      return "dummy-task-list";
    }

    @Override
    public String getNamespace() {
      return "dummy-namespace";
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
    public Duration getWorkflowRunTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Duration getWorkflowExecutionTimeout() {
      return Duration.ZERO;
    }

    @Override
    public long getRunStartedTimestampMillis() {
      return 0;
    }

    @Override
    public long getWorkflowExecutionExpirationTimestampMillis() {
      return 0;
    }

    @Override
    public Duration getWorkflowTaskTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public SearchAttributes getSearchAttributes() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Map<String, Object> getPropagatedContexts() {
      return null;
    }

    @Override
    public List<ContextPropagator> getContextPropagators() {
      return null;
    }

    @Override
    public Consumer<Exception> scheduleActivityTask(
        ExecuteActivityParameters parameters, BiConsumer<Optional<Payloads>, Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Consumer<Exception> scheduleLocalActivityTask(
        ExecuteLocalActivityParameters parameters,
        BiConsumer<Optional<Payloads>, Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Consumer<Exception> startChildWorkflow(
        StartChildWorkflowExecutionParameters parameters,
        Consumer<WorkflowExecution> executionCallback,
        BiConsumer<Optional<Payloads>, Exception> callback) {
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
    public Optional<Payloads> mutableSideEffect(
        String id, DataConverter converter, Func1<Optional<Payloads>, Optional<Payloads>> func) {
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
    public Optional<Payloads> sideEffect(Func<Optional<Payloads>> func) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public int getVersion(
        String changeId, DataConverter converter, int minSupported, int maxSupported) {
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

    @Override
    public void upsertSearchAttributes(SearchAttributes searchAttributes) {
      throw new UnsupportedOperationException("not implemented");
    }
  }
}
