package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.autoconfigure.gracefulshutdown.SlowActivityImpl;
import io.temporal.spring.boot.autoconfigure.gracefulshutdown.SlowWorkflow;
import io.temporal.worker.WorkerFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

public class GracefulShutdownTest {

  @Test
  @Timeout(value = 30)
  public void testInFlightActivityCompletesBeforeContextIsClosed() throws InterruptedException {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(Configuration.class).profiles("graceful-shutdown").run();
    SlowActivityImpl activity = context.getBean(SlowActivityImpl.class);
    WorkerFactory workerFactory = context.getBean(WorkerFactory.class);
    WorkflowClient.start(slowWorkflow(context)::execute);
    assertTrue(activity.awaitStarted(10, TimeUnit.SECONDS));

    context.close();

    assertTrue(activity.isCompleted());
    assertTrue(workerFactory.isTerminated());
  }

  @Test
  @Timeout(value = 30)
  public void testWorkersKeepRunningWhenContextIsPausedAndRestarted() throws Exception {
    assumeTrue(
        Arrays.stream(ConfigurableApplicationContext.class.getMethods())
            .anyMatch(method -> method.getName().equals("pause")),
        "Context pausing requires Spring Framework 7");
    Method pause = ConfigurableApplicationContext.class.getMethod("pause");
    Method restart = ConfigurableApplicationContext.class.getMethod("restart");
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(Configuration.class).profiles("graceful-shutdown").run();
    try {
      pause.invoke(context);
      restart.invoke(context);

      slowWorkflow(context).execute();

      assertTrue(context.getBean(SlowActivityImpl.class).isCompleted());
    } finally {
      context.close();
    }
  }

  private static SlowWorkflow slowWorkflow(ConfigurableApplicationContext context) {
    return context
        .getBean(WorkflowClient.class)
        .newWorkflowStub(
            SlowWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(SlowWorkflow.TASK_QUEUE).build());
  }

  @EnableAutoConfiguration
  public static class Configuration {
    @Bean
    public SlowActivityImpl slowActivity() {
      return new SlowActivityImpl();
    }
  }
}
