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

import com.google.common.util.concurrent.RateLimiter;
import com.uber.cadence.context.ContextPropagator;
import com.uber.cadence.internal.context.ContextThreadLocal;
import com.uber.cadence.internal.logging.LoggerTag;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.internal.replay.DecisionContext;
import com.uber.cadence.workflow.Promise;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class WorkflowThreadImpl implements WorkflowThread {
  private static final RateLimiter metricsRateLimiter = RateLimiter.create(1);

  /**
   * Runnable passed to the thread that wraps a runnable passed to the WorkflowThreadImpl
   * constructor.
   */
  class RunnableWrapper implements Runnable {

    private final WorkflowThreadContext threadContext;
    private final DecisionContext decisionContext;
    private String originalName;
    private String name;
    private CancellationScopeImpl cancellationScope;
    private List<ContextPropagator> contextPropagators;
    private Map<String, Object> propagatedContexts;

    RunnableWrapper(
        WorkflowThreadContext threadContext,
        DecisionContext decisionContext,
        String name,
        boolean detached,
        CancellationScopeImpl parent,
        Runnable runnable,
        List<ContextPropagator> contextPropagators,
        Map<String, Object> propagatedContexts) {
      this.threadContext = threadContext;
      this.decisionContext = decisionContext;
      this.name = name;
      cancellationScope = new CancellationScopeImpl(detached, runnable, parent);
      if (context.getStatus() != Status.CREATED) {
        throw new IllegalStateException("threadContext not in CREATED state");
      }
      this.contextPropagators = contextPropagators;
      this.propagatedContexts = propagatedContexts;
    }

    @Override
    public void run() {
      thread = Thread.currentThread();
      originalName = thread.getName();
      thread.setName(name);
      DeterministicRunnerImpl.setCurrentThreadInternal(WorkflowThreadImpl.this);
      decisionContext.getWorkflowId();
      MDC.put(LoggerTag.WORKFLOW_ID, decisionContext.getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, decisionContext.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, decisionContext.getRunId());
      MDC.put(LoggerTag.TASK_LIST, decisionContext.getTaskList());
      MDC.put(LoggerTag.DOMAIN, decisionContext.getDomain());

      // Repopulate the context(s)
      ContextThreadLocal.setContextPropagators(this.contextPropagators);
      ContextThreadLocal.propagateContextToCurrentThread(this.propagatedContexts);

      try {
        // initialYield blocks thread until the first runUntilBlocked is called.
        // Otherwise r starts executing without control of the sync.
        threadContext.initialYield();
        cancellationScope.run();
      } catch (DestroyWorkflowThreadError e) {
        if (!threadContext.isDestroyRequested()) {
          threadContext.setUnhandledException(e);
        }
      } catch (Error e) {
        // Error aborts decision, not fails the workflow.
        if (log.isErrorEnabled() && !root) {
          StringWriter sw = new StringWriter();
          PrintWriter pw = new PrintWriter(sw, true);
          e.printStackTrace(pw);
          String stackTrace = sw.getBuffer().toString();
          log.error(
              String.format("Workflow thread \"%s\" run failed with Error:\n%s", name, stackTrace));
        }
        threadContext.setUnhandledException(e);
      } catch (CancellationException e) {
        if (!isCancelRequested()) {
          threadContext.setUnhandledException(e);
        }
        if (log.isDebugEnabled()) {
          log.debug(String.format("Workflow thread \"%s\" run cancelled", name));
        }
      } catch (Throwable e) {
        if (log.isWarnEnabled() && !root) {
          StringWriter sw = new StringWriter();
          PrintWriter pw = new PrintWriter(sw, true);
          e.printStackTrace(pw);
          String stackTrace = sw.getBuffer().toString();
          log.warn(
              String.format(
                  "Workflow thread \"%s\" run failed with unhandled exception:\n%s",
                  name, stackTrace));
        }
        threadContext.setUnhandledException(e);
      } finally {
        DeterministicRunnerImpl.setCurrentThreadInternal(null);
        threadContext.setStatus(Status.DONE);
        thread.setName(originalName);
        thread = null;
        MDC.clear();
      }
    }

    public String getName() {
      return name;
    }

    StackTraceElement[] getStackTrace() {
      if (thread != null) {
        return thread.getStackTrace();
      }
      return new StackTraceElement[0];
    }

    public void setName(String name) {
      this.name = name;
      if (thread != null) {
        thread.setName(name);
      }
    }
  }

  private static final Logger log = LoggerFactory.getLogger(WorkflowThreadImpl.class);

  private final boolean root;
  private final ExecutorService threadPool;
  private final WorkflowThreadContext context;
  private DeciderCache cache;
  private final DeterministicRunnerImpl runner;
  private final RunnableWrapper task;
  private Thread thread;
  private Future<?> taskFuture;
  private final Map<WorkflowThreadLocalInternal<?>, Object> threadLocalMap = new HashMap<>();

  /**
   * If not 0 then thread is blocked on a sleep (or on an operation with a timeout). The value is
   * the time in milliseconds (as in currentTimeMillis()) when thread will continue. Note that
   * thread still has to be called for evaluation as other threads might interrupt the blocking
   * call.
   */
  private long blockedUntil;

  WorkflowThreadImpl(
      boolean root,
      ExecutorService threadPool,
      DeterministicRunnerImpl runner,
      String name,
      boolean detached,
      CancellationScopeImpl parentCancellationScope,
      Runnable runnable,
      DeciderCache cache,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    this.root = root;
    this.threadPool = threadPool;
    this.runner = runner;
    this.context = new WorkflowThreadContext(runner.getLock());
    this.cache = cache;

    if (name == null) {
      name = "workflow-" + super.hashCode();
    }

    this.task =
        new RunnableWrapper(
            context,
            runner.getDecisionContext().getContext(),
            name,
            detached,
            parentCancellationScope,
            runnable,
            contextPropagators,
            propagatedContexts);
  }

  @Override
  public void run() {
    throw new UnsupportedOperationException("not used");
  }

  @Override
  public boolean isDetached() {
    return task.cancellationScope.isDetached();
  }

  @Override
  public void cancel() {
    task.cancellationScope.cancel();
  }

  @Override
  public void cancel(String reason) {
    task.cancellationScope.cancel(reason);
  }

  @Override
  public String getCancellationReason() {
    return task.cancellationScope.getCancellationReason();
  }

  @Override
  public boolean isCancelRequested() {
    return task.cancellationScope.isCancelRequested();
  }

  @Override
  public Promise<String> getCancellationRequest() {
    return task.cancellationScope.getCancellationRequest();
  }

  @Override
  public void start() {
    if (context.getStatus() != Status.CREATED) {
      throw new IllegalThreadStateException("already started");
    }
    context.setStatus(Status.RUNNING);

    if (metricsRateLimiter.tryAcquire(1)) {
      getDecisionContext()
          .getMetricsScope()
          .gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT)
          .update(((ThreadPoolExecutor) threadPool).getActiveCount());
    }

    while (true) {
      try {
        taskFuture = threadPool.submit(task);
        return;
      } catch (RejectedExecutionException e) {
        getDecisionContext()
            .getMetricsScope()
            .counter(MetricsType.STICKY_CACHE_THREAD_FORCED_EVICTION)
            .inc(1);
        if (cache != null) {
          boolean evicted =
              cache.evictAnyNotInProcessing(
                  this.runner.getDecisionContext().getContext().getRunId());
          if (!evicted) {
            // Note here we need to throw error, not exception. Otherwise it will be
            // translated to workflow execution exception and instead of failing the
            // decision we will be failing the workflow.
            throw new WorkflowRejectedExecutionError(e);
          }
        } else {
          throw new WorkflowRejectedExecutionError(e);
        }
      }
    }
  }

  public WorkflowThreadContext getContext() {
    return context;
  }

  @Override
  public DeterministicRunnerImpl getRunner() {
    return runner;
  }

  @Override
  public SyncDecisionContext getDecisionContext() {
    return runner.getDecisionContext();
  }

  @Override
  public void setName(String name) {
    task.setName(name);
  }

  @Override
  public String getName() {
    return task.getName();
  }

  @Override
  public long getId() {
    return hashCode();
  }

  @Override
  public long getBlockedUntil() {
    return blockedUntil;
  }

  private void setBlockedUntil(long blockedUntil) {
    this.blockedUntil = blockedUntil;
  }

  /** @return true if coroutine made some progress. */
  @Override
  public boolean runUntilBlocked() {
    if (taskFuture == null) {
      start();
    }
    return context.runUntilBlocked();
  }

  @Override
  public boolean isDone() {
    return context.isDone();
  }

  public Thread.State getState() {
    if (context.getStatus() == Status.YIELDED) {
      return Thread.State.BLOCKED;
    } else if (context.getStatus() == Status.DONE) {
      return Thread.State.TERMINATED;
    } else {
      return Thread.State.RUNNABLE;
    }
  }

  @Override
  public Throwable getUnhandledException() {
    return context.getUnhandledException();
  }

  /**
   * Evaluates function in the threadContext of the coroutine without unblocking it. Used to get
   * current coroutine status, like stack trace.
   *
   * @param function Parameter is reason for current goroutine blockage.
   */
  public void evaluateInCoroutineContext(Consumer<String> function) {
    context.evaluateInCoroutineContext(function);
  }

  /**
   * Interrupt coroutine by throwing DestroyWorkflowThreadError from a await method it is blocked on
   * and return underlying Future to be waited on.
   */
  @Override
  public Future<?> stopNow() {
    // Cannot call destroy() on itself
    if (thread == Thread.currentThread()) {
      throw new Error("Cannot call destroy on itself: " + thread.getName());
    }
    context.destroy();
    if (!context.isDone()) {
      throw new RuntimeException(
          "Couldn't destroy the thread. " + "The blocked thread stack trace: " + getStackTrace());
    }
    if (taskFuture == null) {
      return getCompletedFuture();
    }
    return taskFuture;
  }

  private Future<?> getCompletedFuture() {
    CompletableFuture<String> f = new CompletableFuture<>();
    f.complete("done");
    return f;
  }

  @Override
  public void addStackTrace(StringBuilder result) {
    result.append(getName());
    if (thread == null) {
      result.append("(NEW)");
      return;
    }
    result.append(": (BLOCKED on ").append(getContext().getYieldReason()).append(")\n");
    // These numbers might change if implementation changes.
    int omitTop = 5;
    int omitBottom = 7;
    if (DeterministicRunnerImpl.WORKFLOW_ROOT_THREAD_NAME.equals(getName())) {
      omitBottom = 11;
    }
    StackTraceElement[] stackTrace = thread.getStackTrace();
    for (int i = omitTop; i < stackTrace.length - omitBottom; i++) {
      StackTraceElement e = stackTrace[i];
      if (i == omitTop && "await".equals(e.getMethodName())) continue;
      result.append(e);
      result.append("\n");
    }
  }

  @Override
  public void yield(String reason, Supplier<Boolean> unblockCondition) {
    context.yield(reason, unblockCondition);
  }

  @Override
  public boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    if (timeoutMillis == 0) {
      return unblockCondition.get();
    }
    long blockedUntil = WorkflowInternal.currentTimeMillis() + timeoutMillis;
    setBlockedUntil(blockedUntil);
    YieldWithTimeoutCondition condition =
        new YieldWithTimeoutCondition(unblockCondition, blockedUntil);
    WorkflowThread.await(reason, condition);
    return !condition.isTimedOut();
  }

  @Override
  public <R> void exitThread(R value) {
    runner.exit(value);
    throw new DestroyWorkflowThreadError("exit");
  }

  @Override
  public <T> void setThreadLocal(WorkflowThreadLocalInternal<T> key, T value) {
    threadLocalMap.put(key, value);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<T> getThreadLocal(WorkflowThreadLocalInternal<T> key) {
    if (!threadLocalMap.containsKey(key)) {
      return Optional.empty();
    }
    return Optional.of((T) threadLocalMap.get(key));
  }

  /** @return stack trace of the coroutine thread */
  @Override
  public String getStackTrace() {
    StackTraceElement[] st = task.getStackTrace();
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    for (StackTraceElement se : st) {
      pw.println("\tat " + se);
    }
    return sw.toString();
  }

  static class YieldWithTimeoutCondition implements Supplier<Boolean> {

    private final Supplier<Boolean> unblockCondition;
    private final long blockedUntil;
    private boolean timedOut;

    YieldWithTimeoutCondition(Supplier<Boolean> unblockCondition, long blockedUntil) {
      this.unblockCondition = unblockCondition;
      this.blockedUntil = blockedUntil;
    }

    boolean isTimedOut() {
      return timedOut;
    }

    /** @return true if condition matched or timed out */
    @Override
    public Boolean get() {
      boolean result = unblockCondition.get();
      if (result) {
        return true;
      }
      timedOut = WorkflowInternal.currentTimeMillis() >= blockedUntil;
      return timedOut;
    }
  }
}
