package io.temporal.spring.boot.autoconfigure.internal;

import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

/** Drains workers before Spring destroys their dependencies. */
public final class WorkerFactoryLifecycle implements SmartLifecycle, DisposableBean {
  private final WorkerFactory workerFactory;

  public WorkerFactoryLifecycle(WorkerFactory workerFactory) {
    this.workerFactory = workerFactory;
  }

  @Override
  public boolean isAutoStartup() {
    return false;
  }

  @Override
  public void start() {
    // Worker startup is controlled by the existing application event listeners.
  }

  @Override
  public boolean isRunning() {
    return !workerFactory.isTerminated();
  }

  @Override
  public void stop() {
    shutdown();
    workerFactory.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
  }

  @Override
  public void stop(Runnable callback) {
    shutdown();
    Thread waiter =
        new Thread(
            () -> {
              try {
                workerFactory.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
              } finally {
                callback.run();
              }
            },
            "temporal-worker-shutdown-"
                + workerFactory.getWorkflowClient().getOptions().getNamespace());
    // Spring's lifecycle processor bounds the wait with its shutdown phase timeout.
    waiter.setDaemon(true);
    waiter.start();
  }

  @Override
  public void destroy() {
    // Bean destruction also runs when context initialization fails before lifecycle shutdown.
    shutdown();
  }

  private void shutdown() {
    if (!workerFactory.isShutdown()) {
      workerFactory.shutdown();
    }
  }
}
