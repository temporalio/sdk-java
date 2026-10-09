package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.spring.boot.autoconfigure.internal.WorkerFactoryLifecycle;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.BeansException;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.context.support.GenericApplicationContext;

@Timeout(10)
class WorkerFactoryLifecycleTest {
  @Test
  void boundsContextCloseWithoutInterruptingTasksOrStartingWorkers() throws Exception {
    WorkerFactory factory = mock(WorkerFactory.class);
    WorkflowClient client = mock(WorkflowClient.class);
    when(factory.getWorkflowClient()).thenReturn(client);
    when(client.getOptions()).thenReturn(WorkflowClientOptions.getDefaultInstance());
    CountDownLatch awaitingTermination = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    doAnswer(
            invocation -> {
              awaitingTermination.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                interrupted.set(true);
              } finally {
                finished.countDown();
              }
              return null;
            })
        .when(factory)
        .awaitTermination(anyLong(), any());
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
      processor.setBeanFactory(context.getBeanFactory());
      processor.setTimeoutPerShutdownPhase(100);
      context.getBeanFactory().registerSingleton("lifecycleProcessor", processor);
      context
          .getBeanFactory()
          .registerSingleton("workerLifecycle", new WorkerFactoryLifecycle(factory));
      context.refresh();
      verify(factory, never()).start();
      context.close();
      assertTrue(awaitingTermination.await(1, TimeUnit.SECONDS));
      verify(factory).shutdown();
      verify(factory, never()).shutdownNow();
      assertEquals(1, finished.getCount());
      assertFalse(interrupted.get());
    } finally {
      release.countDown();
      if (awaitingTermination.getCount() == 0) {
        assertTrue(finished.await(1, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void awaitsShutdownAlreadyInitiatedByApplication() {
    WorkerFactory factory = mock(WorkerFactory.class);
    WorkflowClient client = mock(WorkflowClient.class);
    when(factory.getWorkflowClient()).thenReturn(client);
    when(client.getOptions()).thenReturn(WorkflowClientOptions.getDefaultInstance());
    when(factory.isShutdown()).thenReturn(true);
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      context
          .getBeanFactory()
          .registerSingleton("workerLifecycle", new WorkerFactoryLifecycle(factory));
      context.refresh();
      context.close();
      verify(factory, never()).shutdown();
      verify(factory).awaitTermination(anyLong(), any());
    }
  }

  @Test
  void shutsDownWorkersWhenContextInitializationFails() {
    WorkerFactory factory = mock(WorkerFactory.class);
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      context.registerBean(
          "workerLifecycle",
          WorkerFactoryLifecycle.class,
          () -> new WorkerFactoryLifecycle(factory));
      context.registerBean(
          "broken",
          Object.class,
          () -> {
            throw new IllegalStateException("Context initialization failed.");
          },
          definition -> definition.setDependsOn("workerLifecycle"));
      assertThrows(BeansException.class, context::refresh);
      verify(factory).shutdown();
      verify(factory, never()).shutdownNow();
    }
  }
}
