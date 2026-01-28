package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.worker.WorkflowExecutorCache;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Implementation of WorkflowThread that executes a provided Runnable. This is the standard workflow
 * thread implementation used for executing workflow code and async operations.
 */
class WorkflowThreadImpl extends WorkflowThreadBase {

  /** Runnable wrapper that executes the provided runnable within a cancellation scope. */
  class RunnableWrapper extends RunnableWrapperBase {

    RunnableWrapper(
        WorkflowThreadContext threadContext,
        io.temporal.internal.replay.ReplayWorkflowContext replayWorkflowContext,
        String name,
        boolean detached,
        CancellationScopeImpl parent,
        Runnable runnable,
        List<ContextPropagator> contextPropagators,
        Map<String, Object> propagatedContexts) {
      super(
          threadContext,
          replayWorkflowContext,
          name,
          detached,
          parent,
          runnable,
          contextPropagators,
          propagatedContexts);
    }

    @Override
    protected void executeLogic() {
      cancellationScope.run();
    }
  }

  private final Runnable runnable;

  WorkflowThreadImpl(
      WorkflowThreadExecutor workflowThreadExecutor,
      SyncWorkflowContext syncWorkflowContext,
      DeterministicRunnerImpl runner,
      @Nonnull String name,
      int priority,
      boolean detached,
      CancellationScopeImpl parentCancellationScope,
      Runnable runnable,
      WorkflowExecutorCache cache,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    super(workflowThreadExecutor, syncWorkflowContext, runner, priority, cache);
    this.runnable = runnable;
    this.task =
        createTaskWrapper(
            Preconditions.checkNotNull(name, "Thread name shouldn't be null"),
            detached,
            parentCancellationScope,
            runnable,
            contextPropagators,
            propagatedContexts);
  }

  @Override
  protected RunnableWrapperBase createTaskWrapper(
      @Nonnull String name,
      boolean detached,
      CancellationScopeImpl parentCancellationScope,
      Runnable runnable,
      List<ContextPropagator> contextPropagators,
      Map<String, Object> propagatedContexts) {
    return new RunnableWrapper(
        context,
        syncWorkflowContext.getReplayContext(),
        name,
        detached,
        parentCancellationScope,
        runnable,
        contextPropagators,
        propagatedContexts);
  }
}
