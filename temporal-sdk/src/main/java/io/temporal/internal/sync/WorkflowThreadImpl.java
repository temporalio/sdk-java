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
import io.temporal.common.context.ContextPropagator;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.context.ContextThreadLocal;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class WorkflowThreadImpl implements WorkflowThread {
  /**
   * Runnable passed to the thread that wraps a runnable passed to the WorkflowThreadImpl
   * constructor.
   */
  class RunnableWrapper implements Runnable {

    private final WorkflowThreadContext threadContext;
    // TODO: Move MDC injection logic into an interceptor as this context shouldn't be leaked
    // to the WorkflowThreadImpl
    private final ReplayWorkflowContext replayWorkflowContext;
    private String originalName;
    private String name;
    private final CancellationScopeImpl cancellationScope;
    private final List<ContextPropagator> contextPropagators;
    private final Map<String, Object> propagatedContexts;

    RunnableWrapper(
        WorkflowThreadContext threadContext,
        ReplayWorkflowContext replayWorkflowContext,
        String name,
        boolean detached,
        CancellationScopeImpl parent,
        Runnable runnable,
        List<ContextPropagator> contextPropagators,
        Map<String, Object> propagatedContexts) {
      this.threadContext = threadContext;
      this.replayWorkflowContext = replayWorkflowContext;
      this.name = name;
      this.cancellationScope = new CancellationScopeImpl(detached, runnable, parent);
      if (context.getStatus() != Status.CREATED) {
        throw new IllegalStateException("threadContext not in CREATED state");
      }
      this.contextPropagators = contextPropagators;
      this.propagatedContexts = propagatedContexts;
    }

    @Override
    public void run() {
      Thread thread = Thread.currentThread();
      originalName = thread.getName();
      thread.setName(name);

      threadContext.initializeCurrentThread(thread);
      DeterministicRunnerImpl.setCurrentThreadInternal(WorkflowThreadImpl.this);

      MDC.put(LoggerTag.WORKFLOW_ID, replayWorkflowContext.getWorkflowId());
      MDC.put(LoggerTag.WORKFLOW_TYPE, replayWorkflowContext.getWorkflowType().getName());
      MDC.put(LoggerTag.RUN_ID, replayWorkflowContext.getRunId());
      MDC.put(LoggerTag.TASK_QUEUE, replayWorkflowContext.getTaskQueue());
      MDC.put(LoggerTag.NAMESPACE, replayWorkflowContext.getNamespace());

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
        threadContext.setUnhandledException(e);
      } catch (CanceledFailure e) {
        if (!isCancelRequested()) {
          threadContext.setUnhandledException(e);
        }
        if (log.isDebugEnabled()) {
          log.debug(String.format("Workflow thread \"%s\" run canceled", name));
        }
      } catch (Throwable e) {
        threadContext.setUnhandledException(e);
      } finally {
        DeterministicRunnerImpl.setCurrentThreadInternal(null);
        threadContext.setStatus(Status.DONE);
        thread.setName(originalName);
        MDC.clear();
      }
    }

    public String getName() {
      return name;
    }

    StackTraceElement[] getStackTrace() {
      @Nullable Thread thread = threadContext.getCurrentThread();
      if (thread != null) {
        return thread.getStackTrace();
      }
      return new StackTraceElement[0];
    }

    public void setName(String name) {
      this.name = name;
      @Nullable Thread thread = threadContext.getCurrentThread();
      if (thread != null) {
        thread.setName(name);
      }
    }
  }

  private static final Logger log = LoggerFactory.getLogger(WorkflowThreadImpl.class);

  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final WorkflowThreadContext context;
  private final WorkflowExecutorCache cache;
  private final DeterministicRunnerImpl runner;
  private final RunnableWrapper task;
  private final int priority;
  private Future<?> taskFuture;
  private final Map<WorkflowThreadLocalInternal<?>, Object> threadLocalMap = new HashMap<>();

  WorkflowThreadImpl(
      WorkflowThreadExecutor workflowThreadExecutor,
      DeterministicRunnerImpl runner,
      @Nonnull String name,
      int priority,
      boolean detached,
      CancellationScopeImpl parentCancellationScope,
      Runnable runnable,
      WorkflowExecutorCache cache,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    Preconditions.checkNotNull(name, "Thread name shouldn't be null");
    this.workflowThreadExecutor = workflowThreadExecutor;
    this.runner = runner;
    this.context = new WorkflowThreadContext(runner.getLock());
    this.cache = cache;
    this.priority = priority;
    this.task =
        new RunnableWrapper(
            context,
            runner.getWorkflowContext().getContext(),
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

    while (true) {
      try {
        taskFuture = workflowThreadExecutor.submit(task);
        return;
      } catch (RejectedExecutionException e) {
        if (cache != null) {
          SyncWorkflowContext workflowContext = getWorkflowContext();
          ReplayWorkflowContext context = workflowContext.getContext();
          boolean evicted =
              cache.evictAnyNotInProcessing(
                  context.getWorkflowExecution(), workflowContext.getMetricsScope());
          if (!evicted) {
            // Note here we need to throw error, not exception. Otherwise it will be
            // translated to workflow execution exception and instead of failing the
            // workflow task we will be failing the workflow.
            throw new WorkflowRejectedExecutionError(e);
          }
        } else {
          throw new WorkflowRejectedExecutionError(e);
        }
      }
    }
  }

  @Override
  public boolean isStarted() {
    return context.getStatus() != Status.CREATED;
  }

  @Override
  public WorkflowThreadContext getWorkflowThreadContext() {
    return context;
  }

  @Override
  public DeterministicRunnerImpl getRunner() {
    return runner;
  }

  @Override
  public SyncWorkflowContext getWorkflowContext() {
    return runner.getWorkflowContext();
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
  public int getPriority() {
    return priority;
  }

  @Override
  public boolean runUntilBlocked(long deadlockDetectionTimeoutMs) {
    if (taskFuture == null) {
      start();
    }
    return context.runUntilBlocked(deadlockDetectionTimeoutMs);
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
  public void evaluateInCoroutineContext(Functions.Proc1<String> function) {
    context.evaluateInCoroutineContext(function);
  }

  /**
   * Interrupt coroutine by throwing DestroyWorkflowThreadError from an await method it is blocked
   * on and return underlying Future to be waited on.
   */
  @Override
  public Future<?> stopNow() {
    // Cannot call destroy() on itself
    @Nullable Thread thread = context.getCurrentThread();
    if (Thread.currentThread().equals(thread)) {
      throw new Error("Cannot call destroy on itself: " + thread.getName());
    }
    context.initiateDestroy();
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
    @Nullable Thread thread = context.getCurrentThread();
    if (thread == null) {
      result.append("(NEW)");
      return;
    }
    result
        .append(": (BLOCKED on ")
        .append(getWorkflowThreadContext().getYieldReason())
        .append(")\n");
    // These numbers might change if implementation changes.
    int omitTop = 5;
    int omitBottom = 7;
    // TODO it's not a good idea to rely on the name to understand the thread type. Instead of that
    // we would better
    // assign an explicit thread type enum to the threads. This will be especially important when we
    // refactor
    // root and workflow-method
    // thread names into names that will include workflowId
    if (DeterministicRunnerImpl.WORKFLOW_ROOT_THREAD_NAME.equals(getName())) {
      // TODO revisit this number
      omitBottom = 11;
    } else if (getName().startsWith(WorkflowMethodThreadNameStrategy.WORKFLOW_MAIN_THREAD_PREFIX)) {
      // TODO revisit this number
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

  /**
   * @return stack trace of the coroutine thread
   */
  @Override
  public String getStackTrace() {
    StackTraceElement[] st = task.getStackTrace();
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    pw.append(task.getName());
    pw.append("\n");
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

    /**
     * @return true if condition matched or timed out
     */
    @Override
    public Boolean get() {
      boolean result = unblockCondition.get();
      if (result) {
        return true;
      }
      long currentTimeMillis = WorkflowInternal.currentTimeMillis();
      timedOut = currentTimeMillis >= blockedUntil;
      return timedOut;
    }
  }
}
