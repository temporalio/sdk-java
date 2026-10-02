package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.autoconfigure.gracefulshutdown.SlowActivityImpl;
import io.temporal.spring.boot.autoconfigure.gracefulshutdown.SlowWorkflow;
import io.temporal.worker.WorkerFactory;
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
    SlowWorkflow workflow =
        context
            .getBean(WorkflowClient.class)
            .newWorkflowStub(
                SlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(SlowWorkflow.TASK_QUEUE).build());
    WorkflowClient.start(workflow::execute);
    assertTrue(activity.awaitStarted(10, TimeUnit.SECONDS));

    context.close();

    assertTrue(activity.isCompleted());
    assertTrue(workerFactory.isTerminated());
  }

  @EnableAutoConfiguration
  public static class Configuration {
    @Bean
    public SlowActivityImpl slowActivity() {
      return new SlowActivityImpl();
    }
  }
}
