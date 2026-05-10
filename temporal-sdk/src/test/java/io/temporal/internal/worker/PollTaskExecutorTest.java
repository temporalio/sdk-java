package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class PollTaskExecutorTest {

  @Test
  public void testContextClassLoaderInherited() throws Exception {
    PollerOptions pollerOptions =
        PollerOptions.newBuilder().setPollThreadNamePrefix("test").build();

    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    ClassLoader testClassLoader = new ClassLoader() {};
    Thread.currentThread().setContextClassLoader(testClassLoader);

    AtomicReference<ClassLoader> executedClassLoader = new AtomicReference<>();

    try {
      PollTaskExecutor<String> executor =
          new PollTaskExecutor<>(
              "namespace",
              "taskQueue",
              "identity",
              new PollTaskExecutor.TaskHandler<String>() {
                @Override
                public void handle(String task) {
                  executedClassLoader.set(Thread.currentThread().getContextClassLoader());
                }

                @Override
                public Throwable wrapFailure(String task, Throwable failure) {
                  return failure;
                }
              },
              pollerOptions,
              1,
              false);

      // Execute on a different thread with a different context class loader to simulate
      // ForkJoinPool
      CompletableFuture<Void> future = new CompletableFuture<>();
      Thread triggerThread =
          new Thread(
              () -> {
                Thread.currentThread().setContextClassLoader(null);
                executor.process("task");
                future.complete(null);
              });
      triggerThread.start();
      future.get(5, TimeUnit.SECONDS);

      // Wait for task completion
      executor.shutdown(new ShutdownManager(), false).get(5, TimeUnit.SECONDS);

      assertEquals(testClassLoader, executedClassLoader.get());
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }
  }
}
