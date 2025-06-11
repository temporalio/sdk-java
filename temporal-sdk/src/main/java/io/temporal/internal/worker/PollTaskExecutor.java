package io.temporal.internal.worker;

import com.google.common.base.Preconditions;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.internal.task.VirtualThreadDelegate;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.slf4j.MDC;

final class PollTaskExecutor<T> implements ShutdownableTaskExecutor<T> {

  public interface TaskHandler<TT> {
    void handle(TT task) throws Exception;

    Throwable wrapFailure(TT task, Throwable failure);
  }

  private final String namespace;
  private final String taskQueue;
  private final String identity;
  private final TaskHandler<T> handler;
  private final PollerOptions pollerOptions;

  private final ExecutorService taskExecutor;
  private final String pollThreadNamePrefix;

  PollTaskExecutor(
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nonnull TaskHandler<T> handler,
      @Nonnull PollerOptions pollerOptions,
      int threadPoolMax,
      boolean useVirtualThreads) {
    this.namespace = Objects.requireNonNull(namespace);
    this.taskQueue = Objects.requireNonNull(taskQueue);
    this.identity = Objects.requireNonNull(identity);
    this.handler = Objects.requireNonNull(handler);
    this.pollerOptions = Objects.requireNonNull(pollerOptions);

    this.pollThreadNamePrefix =
        pollerOptions.getPollThreadNamePrefix().replaceFirst("Poller", "Executor");
    if (pollerOptions.getPollerTaskExecutorOverride() != null) {
      this.taskExecutor = pollerOptions.getPollerTaskExecutorOverride();
    } else if (useVirtualThreads) {
      // If virtual threads are enabled, we use a virtual thread executor.
      AtomicInteger threadIndex = new AtomicInteger();
      this.taskExecutor =
          VirtualThreadDelegate.newVirtualThreadExecutor(
              (t) -> {
                t.setName(this.pollThreadNamePrefix + ": " + threadIndex.incrementAndGet());
                t.setUncaughtExceptionHandler(pollerOptions.getUncaughtExceptionHandler());
              });
    } else {
      ThreadPoolExecutor threadPoolTaskExecutor =
          new ThreadPoolExecutor(0, threadPoolMax, 10, TimeUnit.SECONDS, new SynchronousQueue<>());
      threadPoolTaskExecutor.allowCoreThreadTimeOut(true);
      threadPoolTaskExecutor.setThreadFactory(
          new ExecutorThreadFactory(
              this.pollThreadNamePrefix, pollerOptions.getUncaughtExceptionHandler()));
      threadPoolTaskExecutor.setRejectedExecutionHandler(new BlockCallerPolicy());
      this.taskExecutor = threadPoolTaskExecutor;
    }
  }

  @Override
  public void process(@Nonnull T task) {
    Preconditions.checkNotNull(task, "task");
    taskExecutor.execute(
        () -> {
          try {
            MDC.put(LoggerTag.NAMESPACE, namespace);
            MDC.put(LoggerTag.TASK_QUEUE, taskQueue);
            handler.handle(task);
          } catch (Throwable e) {
            if (!isShutdown()) {
              pollerOptions
                  .getUncaughtExceptionHandler()
                  .uncaughtException(Thread.currentThread(), handler.wrapFailure(task, e));
            }
          } finally {
            MDC.clear();
          }
        });
  }

  @Override
  public boolean isShutdown() {
    return taskExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return taskExecutor.isTerminated();
  }

  @Override
  public CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptTasks) {
    String taskExecutorName = this + "#taskExecutor";
    return interruptTasks
        ? shutdownManager.shutdownExecutorNowUntimed(taskExecutor, taskExecutorName)
        : shutdownManager.shutdownExecutorUntimed(taskExecutor, taskExecutorName);
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    ShutdownManager.awaitTermination(taskExecutor, unit.toMillis(timeout));
  }

  @Override
  public String toString() {
    // TODO using pollThreadNamePrefix here is ugly. We should consider introducing some concept of
    // WorkerContext [workerIdentity, namespace, queue, local/non-local if applicable] and pass it
    // around
    // that will simplify such kind of logging through workers.
    return String.format("PollTaskExecutor{name=%s, identity=%s}", pollThreadNamePrefix, identity);
  }
}
