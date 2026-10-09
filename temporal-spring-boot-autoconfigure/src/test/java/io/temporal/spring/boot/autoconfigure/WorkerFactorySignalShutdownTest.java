package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;

@Timeout(90)
class WorkerFactorySignalShutdownTest {
  @TempDir Path directory;

  @EnabledOnOs({OS.LINUX, OS.MAC})
  @ParameterizedTest
  @CsvSource({"spring, INT", "spring, TERM", "plain, INT", "plain, TERM"})
  void drainsActivitiesOnSignal(String hosting, String signal) throws Exception {
    runChild(hosting, signal);
  }

  @Test
  void drainsActivitiesOnContextCloseWithoutJvmHook() throws Exception {
    runChild("spring", "close");
  }

  @Test
  void closingChildContextLeavesParentWorkersRunning() throws Exception {
    runChild("spring", "child-close");
  }

  private static boolean manualClose(String action) {
    return "close".equals(action) || "child-close".equals(action);
  }

  private void runChild(String hosting, String signal) throws Exception {
    Path output = directory.resolve("output.log");
    Process child =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("temporal.test.classpath"),
                Child.class.getName(),
                directory.toString(),
                hosting,
                signal)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      await(() -> Files.exists(directory.resolve("ready")), child, output);
      if (manualClose(signal)) {
        Files.createFile(directory.resolve("close"));
      } else {
        String pid = read(directory.resolve("pid"));
        Process kill = new ProcessBuilder("kill", "-" + signal, pid).start();
        assertTrue(kill.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, kill.exitValue());
      }
      await(() -> Files.exists(directory.resolve("root-shutdown")), child, output);
      if ("spring".equals(hosting)) {
        await(() -> Files.exists(directory.resolve("secondary-shutdown")), child, output);
      }
      assertTrue(child.isAlive(), read(output));
      assertFalse(Files.exists(directory.resolve("terminated")));
      Files.createFile(directory.resolve("release"));
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), read(output));
      assertTrue(Files.exists(directory.resolve("root-completed")), read(output));
      if ("spring".equals(hosting)) {
        assertTrue(Files.exists(directory.resolve("secondary-completed")), read(output));
      }
      assertTrue(Files.exists(directory.resolve("terminated")), read(output));
      if (manualClose(signal)) {
        assertEquals(0, child.exitValue(), read(output));
      }
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Child JVM did not exit.");
    }
  }

  private static void await(BooleanSupplier condition, Process child, Path output)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
    while (!condition.getAsBoolean() && child.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), read(output));
  }

  private static String read(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
  }

  @WorkflowInterface
  public interface DrainWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class DrainWorkflowImpl implements DrainWorkflow {
    @Override
    public void run() {
      Workflow.newActivityStub(
              DrainActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build())
          .run();
    }
  }

  @ActivityInterface
  public interface DrainActivity {
    @ActivityMethod
    void run();
  }

  public static class Child {
    private static Path directory;
    private static TestWorkflowEnvironment environment;

    public static void main(String[] args) throws Exception {
      directory = Paths.get(args[0]);
      environment = TestWorkflowEnvironment.newInstance();
      if ("plain".equals(args[1])) {
        WorkerFactory factory = environment.getWorkerFactory();
        Worker worker = factory.newWorker("root");
        worker.registerWorkflowImplementationTypes(DrainWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity("root", () -> factory));
        factory.start();
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      factory.shutdown();
                      factory.awaitTermination(30, TimeUnit.SECONDS);
                      verifyTermination(factory);
                      environment.close();
                    }));
        startWorkflow(factory, "root");
        ready();
        new CountDownLatch(1).await();
      } else {
        ConfigurableApplicationContext context =
            new SpringApplicationBuilder(ChildConfiguration.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(!manualClose(args[2]))
                .run(
                    "--spring.config.name=signal-shutdown-test",
                    "--spring.temporal.test-server.enabled=false",
                    "--spring.temporal.connection.target=127.0.0.1:7233",
                    "--spring.lifecycle.timeout-per-shutdown-phase=20s",
                    "--spring.temporal.workers[0].task-queue=root",
                    "--spring.temporal.workers[0].workflow-classes[0]="
                        + DrainWorkflowImpl.class.getName(),
                    "--spring.temporal.workers[0].activity-beans[0]=rootActivity",
                    "--spring.temporal.namespaces[0].namespace=default",
                    "--spring.temporal.namespaces[0].alias=secondary",
                    "--spring.temporal.namespaces[0].workers[0].task-queue=secondary",
                    "--spring.temporal.namespaces[0].workers[0].workflow-classes[0]="
                        + DrainWorkflowImpl.class.getName(),
                    "--spring.temporal.namespaces[0].workers[0].activity-beans[0]=secondaryActivity");
        WorkerFactory root = context.getBean("temporalWorkerFactory", WorkerFactory.class);
        WorkerFactory secondary = context.getBean("secondaryWorkerFactory", WorkerFactory.class);
        assertNotSame(root, secondary);
        if ("child-close".equals(args[2])) {
          try (GenericApplicationContext unrelated = new GenericApplicationContext(context)) {
            unrelated.refresh();
          }
          assertFalse(root.isShutdown());
          assertFalse(secondary.isShutdown());
        }
        startWorkflow(root, "root");
        startWorkflow(secondary, "secondary");
        ready();
        if (manualClose(args[2])) {
          while (!Files.exists(directory.resolve("close"))) {
            Thread.sleep(20);
          }
          context.close();
        } else {
          new CountDownLatch(1).await();
        }
      }
    }

    private static DrainActivity activity(String name, Supplier<WorkerFactory> factory) {
      return () -> {
        try {
          Files.createFile(directory.resolve(name + "-started"));
          while (!factory.get().isShutdown()) {
            Thread.sleep(20);
          }
          Files.createFile(directory.resolve(name + "-shutdown"));
          while (!Files.exists(directory.resolve("release"))) {
            Thread.sleep(20);
          }
          Files.createFile(directory.resolve(name + "-completed"));
        } catch (IOException | InterruptedException e) {
          throw new RuntimeException(e);
        }
      };
    }

    private static void startWorkflow(WorkerFactory factory, String queue) {
      DrainWorkflow workflow =
          factory
              .getWorkflowClient()
              .newWorkflowStub(
                  DrainWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(queue).build());
      WorkflowClient.start(workflow::run);
    }

    private static void ready() throws Exception {
      while (!Files.exists(directory.resolve("root-started"))
          || (Files.exists(directory.resolve("spring"))
              && !Files.exists(directory.resolve("secondary-started")))) {
        Thread.sleep(20);
      }
      Files.write(
          directory.resolve("pid"),
          ManagementFactory.getRuntimeMXBean()
              .getName()
              .split("@")[0]
              .getBytes(StandardCharsets.UTF_8));
      Files.createFile(directory.resolve("ready"));
    }

    private static void verifyTermination(WorkerFactory... factories) {
      for (WorkerFactory factory : factories) {
        assertTrue(factory.isShutdown());
        assertTrue(factory.isTerminated());
      }
      try {
        Files.createFile(directory.resolve("terminated"));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @EnableAutoConfiguration
  public static class ChildConfiguration {
    @Bean
    TestWorkflowEnvironment signalTestEnvironment() throws IOException {
      Files.createFile(Child.directory.resolve("spring"));
      return Child.environment;
    }

    @Bean
    TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder> workflowServiceStubsCustomizer(
        TestWorkflowEnvironment environment) {
      return builder ->
          builder.setTarget(null).setChannel(environment.getWorkflowServiceStubs().getRawChannel());
    }

    @Bean
    TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>
        secondaryWorkflowServiceStubsCustomizer(TestWorkflowEnvironment environment) {
      return builder ->
          builder.setTarget(null).setChannel(environment.getWorkflowServiceStubs().getRawChannel());
    }

    @Bean
    DrainActivity rootActivity(
        @Qualifier("temporalWorkerFactory") ObjectProvider<WorkerFactory> factory) {
      return Child.activity("root", factory::getObject);
    }

    @Bean
    DrainActivity secondaryActivity(
        @Qualifier("secondaryWorkerFactory") ObjectProvider<WorkerFactory> factory) {
      return Child.activity("secondary", factory::getObject);
    }

    @Bean
    DisposableBean drainVerifier(
        @Qualifier("temporalWorkerFactory") WorkerFactory root,
        @Qualifier("secondaryWorkerFactory") WorkerFactory secondary) {
      return () -> Child.verifyTermination(root, secondary);
    }
  }
}
