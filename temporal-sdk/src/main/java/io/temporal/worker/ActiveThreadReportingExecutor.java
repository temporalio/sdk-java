package io.temporal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * This class implements a {@link WorkflowThreadExecutor} that report an amount of active tasks
 * using {@link MetricsType#WORKFLOW_ACTIVE_THREAD_COUNT} counter. Implemented as a custom
 * AtomicInteger instead of using {@link ThreadPoolExecutor#getActiveCount()} for performance
 * reasons. {@link ThreadPoolExecutor#getActiveCount()} take a pool-wide lock.
 */
class ActiveThreadReportingExecutor implements WorkflowThreadExecutor {
  private final ExecutorService workflowThreadPool;
  private final Scope metricsScope;
  private final AtomicInteger tasksInFlight = new AtomicInteger();

  ActiveThreadReportingExecutor(ExecutorService workflowThreadPool, Scope metricsScope) {
    this.workflowThreadPool = workflowThreadPool;
    this.metricsScope = metricsScope;
  }

  @Override
  public Future<?> submit(@Nonnull Runnable task) {
    return workflowThreadPool.submit(
        () -> {
          int tasksCount = tasksInFlight.incrementAndGet();
          metricsScope.gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT).update(tasksCount);
          try {
            task.run();
          } finally {
            tasksCount = tasksInFlight.decrementAndGet();
            metricsScope.gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT).update(tasksCount);
          }
        });
  }
}
