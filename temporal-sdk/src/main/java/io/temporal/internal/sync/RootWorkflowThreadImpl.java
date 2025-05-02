package io.temporal.internal.sync;

import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.worker.WorkflowExecutorCache;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RootWorkflowThreadImpl extends WorkflowThreadImpl {
  private static final Logger log = LoggerFactory.getLogger(RootWorkflowThreadImpl.class);

  RootWorkflowThreadImpl(
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
    super(
        workflowThreadExecutor,
        syncWorkflowContext,
        runner,
        name,
        priority,
        detached,
        parentCancellationScope,
        runnable,
        cache,
        contextPropagators,
        propagatedContexts);
  }

  @Override
  public void yield(String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    log.warn(
        "Detected root workflow thread yielding {}. This can happen by making blocking calls during workflow instance creating, such as executing an activity inside a @WorkflowInit constructor. This can cause issues, like Queries or Updates rejection because the workflow instance creation is delayed",
        getName());
    super.yield(reason, unblockCondition);
  }
}
