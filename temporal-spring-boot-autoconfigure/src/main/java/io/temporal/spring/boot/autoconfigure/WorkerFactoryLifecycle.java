package io.temporal.spring.boot.autoconfigure;

import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * Shuts down a started {@link WorkerFactory} when the application context closes and waits for its
 * workers to terminate, so that in-flight tasks complete before the beans they use, including the
 * service stubs, are destroyed. The wait is bounded by {@code
 * spring.lifecycle.timeout-per-shutdown-phase}. The factory is started elsewhere. A shut down
 * factory cannot be started again, so the factory keeps running while the context is paused.
 */
public class WorkerFactoryLifecycle implements SmartLifecycle {

  private static final String AWAITER_THREAD_NAME = "temporal-worker-factory-stop";

  private final WorkerFactory workerFactory;

  public WorkerFactoryLifecycle(WorkerFactory workerFactory) {
    this.workerFactory = workerFactory;
  }

  @Override
  public boolean isAutoStartup() {
    return false;
  }

  public boolean isPauseable() {
    return false;
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    workerFactory.shutdown();
    workerFactory.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
  }

  @Override
  public void stop(Runnable callback) {
    workerFactory.shutdown();
    Thread awaiter =
        new Thread(
            () -> {
              try {
                workerFactory.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
              } finally {
                callback.run();
              }
            },
            AWAITER_THREAD_NAME);
    awaiter.setDaemon(true);
    awaiter.start();
  }

  @Override
  public boolean isRunning() {
    return workerFactory.isStarted() && !workerFactory.isShutdown();
  }
}
