package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowClientPlugin;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleClientPlugin;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import io.temporal.worker.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = PluginAutoWiringTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-task-queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PluginAutoWiringTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired WorkflowClient workflowClient;

  @Autowired WorkerFactory temporalWorkerFactory;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testPluginsAreAutoWired() {
    // Verify that plugin beans are present in the application context
    assertNotNull(applicationContext.getBean("clientPlugin"));
    assertNotNull(applicationContext.getBean("workerPlugin"));
    assertNotNull(applicationContext.getBean("comboPlugin"));
  }

  @Test
  @Timeout(value = 10)
  public void testClientPluginGetsCalled() {
    // Verify client plugin got its configure method called by checking the recorded invocations
    assertTrue(
        Configuration.clientPluginInvocations.contains("configureWorkflowClient"),
        "Client plugin configureWorkflowClient should have been called");
  }

  @Test
  @Timeout(value = 10)
  public void testWorkerPluginGetsCalled() {
    // Verify worker plugin got its configure method called
    assertTrue(
        Configuration.workerPluginInvocations.contains("configureWorkerFactory"),
        "Worker plugin configureWorkerFactory should have been called");
  }

  @Test
  @Timeout(value = 10)
  public void testPluginFiltering() {
    // The combo plugin implements WorkflowServiceStubsPlugin, WorkflowClientPlugin,
    // ScheduleClientPlugin,
    // and WorkerPlugin. Because it implements WorkflowServiceStubsPlugin (the highest level), it
    // gets
    // filtered out from all lower levels (client, schedule, worker) to avoid double-registration.
    //
    // In normal (non-test) mode, the plugin would be registered at the stubs level and the SDK's
    // plugin propagation system would call its lower-level methods.
    //
    // In test mode, WorkflowServiceStubsPlugin is not supported (TestWorkflowEnvironment creates
    // its
    // own server), so the combo plugin's configuration methods are NOT called at all.
    //
    // This verifies the filtering is working correctly:
    // - Combo plugin should NOT have client/worker methods called directly (filtered out)
    assertFalse(
        Configuration.comboPluginInvocations.contains("configureWorkflowClient"),
        "Combo plugin should be filtered out at client level (implements WorkflowServiceStubsPlugin)");
    assertFalse(
        Configuration.comboPluginInvocations.contains("configureWorkerFactory"),
        "Combo plugin should be filtered out at worker level (implements WorkflowServiceStubsPlugin)");

    // The regular (non-combo) client and worker plugins should still be called
    assertTrue(
        Configuration.clientPluginInvocations.contains("configureWorkflowClient"),
        "Regular client plugin should still be called");
    assertTrue(
        Configuration.workerPluginInvocations.contains("configureWorkerFactory"),
        "Regular worker plugin should still be called");
  }

  @Test
  @Timeout(value = 10)
  public void testPluginOrdering() {
    // Verify that plugin ordering is applied (via @Order annotation)
    // The ordered plugins should have their callbacks in the expected order
    List<String> record = Configuration.orderedPluginInvocations;

    int firstIndex = record.indexOf("first");
    int secondIndex = record.indexOf("second");
    int thirdIndex = record.indexOf("third");

    assertTrue(firstIndex >= 0, "First ordered plugin should have been called");
    assertTrue(secondIndex >= 0, "Second ordered plugin should have been called");
    assertTrue(thirdIndex >= 0, "Third ordered plugin should have been called");

    assertTrue(firstIndex < secondIndex, "First should be called before second");
    assertTrue(secondIndex < thirdIndex, "Second should be called before third");
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {
    // Track invocations for verification
    static List<String> clientPluginInvocations = Collections.synchronizedList(new ArrayList<>());
    static List<String> workerPluginInvocations = Collections.synchronizedList(new ArrayList<>());
    static List<String> comboPluginInvocations = Collections.synchronizedList(new ArrayList<>());
    static List<String> orderedPluginInvocations = Collections.synchronizedList(new ArrayList<>());

    // WorkflowClientPlugin only - middle level
    @Bean
    public TestClientPlugin clientPlugin() {
      return new TestClientPlugin(clientPluginInvocations);
    }

    // WorkerPlugin only - lowest level
    @Bean
    public TestWorkerPlugin workerPlugin() {
      return new TestWorkerPlugin(workerPluginInvocations);
    }

    // Combo plugin implementing all interfaces - tests filtering behavior
    @Bean
    public TestComboPlugin comboPlugin() {
      return new TestComboPlugin(comboPluginInvocations);
    }

    // Ordered plugins to test @Order support
    @Bean
    @Order(1)
    public WorkerPlugin firstOrderedPlugin() {
      return new OrderedWorkerPlugin("first", orderedPluginInvocations);
    }

    @Bean
    @Order(2)
    public WorkerPlugin secondOrderedPlugin() {
      return new OrderedWorkerPlugin("second", orderedPluginInvocations);
    }

    @Bean
    @Order(3)
    public WorkerPlugin thirdOrderedPlugin() {
      return new OrderedWorkerPlugin("third", orderedPluginInvocations);
    }
  }

  public static class TestClientPlugin implements WorkflowClientPlugin {
    private final List<String> invocations;

    public TestClientPlugin(List<String> invocations) {
      this.invocations = invocations;
    }

    @Nonnull
    @Override
    public String getName() {
      return "test.client-plugin";
    }

    @Override
    public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
      invocations.add("configureWorkflowClient");
    }
  }

  public static class TestWorkerPlugin implements WorkerPlugin {
    private final List<String> invocations;

    public TestWorkerPlugin(List<String> invocations) {
      this.invocations = invocations;
    }

    @Nonnull
    @Override
    public String getName() {
      return "test.worker-plugin";
    }

    @Override
    public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
      invocations.add("configureWorkerFactory");
    }

    @Override
    public void configureWorker(
        @Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {}

    @Override
    public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {}

    @Override
    public void startWorker(
        @Nonnull String taskQueue,
        @Nonnull Worker worker,
        @Nonnull BiConsumer<String, Worker> next) {
      next.accept(taskQueue, worker);
    }

    @Override
    public void shutdownWorker(
        @Nonnull String taskQueue,
        @Nonnull Worker worker,
        @Nonnull BiConsumer<String, Worker> next) {
      next.accept(taskQueue, worker);
    }

    @Override
    public void startWorkerFactory(
        @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
      next.accept(factory);
    }

    @Override
    public void shutdownWorkerFactory(
        @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
      next.accept(factory);
    }

    @Override
    public void replayWorkflowExecution(
        @Nonnull Worker worker,
        @Nonnull io.temporal.common.WorkflowExecutionHistory history,
        @Nonnull ReplayCallback next)
        throws Exception {
      next.replay(worker, history);
    }
  }

  // Plugin that implements multiple interfaces - tests filtering behavior
  public static class TestComboPlugin
      implements WorkflowServiceStubsPlugin,
          WorkflowClientPlugin,
          ScheduleClientPlugin,
          WorkerPlugin {
    private final List<String> invocations;

    public TestComboPlugin(List<String> invocations) {
      this.invocations = invocations;
    }

    @Nonnull
    @Override
    public String getName() {
      return "test.combo-plugin";
    }

    @Override
    public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
      invocations.add("configureServiceStubs");
    }

    @Nonnull
    @Override
    public WorkflowServiceStubs connectServiceClient(
        @Nonnull WorkflowServiceStubsOptions options,
        @Nonnull Supplier<WorkflowServiceStubs> next) {
      invocations.add("connectServiceClient");
      return next.get();
    }

    @Override
    public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
      invocations.add("configureWorkflowClient");
    }

    @Override
    public void configureScheduleClient(@Nonnull ScheduleClientOptions.Builder builder) {
      invocations.add("configureScheduleClient");
    }

    @Override
    public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
      invocations.add("configureWorkerFactory");
    }

    @Override
    public void configureWorker(
        @Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {}

    @Override
    public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {}

    @Override
    public void startWorker(
        @Nonnull String taskQueue,
        @Nonnull Worker worker,
        @Nonnull BiConsumer<String, Worker> next) {
      next.accept(taskQueue, worker);
    }

    @Override
    public void shutdownWorker(
        @Nonnull String taskQueue,
        @Nonnull Worker worker,
        @Nonnull BiConsumer<String, Worker> next) {
      next.accept(taskQueue, worker);
    }

    @Override
    public void startWorkerFactory(
        @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
      next.accept(factory);
    }

    @Override
    public void shutdownWorkerFactory(
        @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
      next.accept(factory);
    }

    @Override
    public void replayWorkflowExecution(
        @Nonnull Worker worker,
        @Nonnull io.temporal.common.WorkflowExecutionHistory history,
        @Nonnull ReplayCallback next)
        throws Exception {
      next.replay(worker, history);
    }
  }

  // Plugin for testing ordering
  public static class OrderedWorkerPlugin implements WorkerPlugin {
    private final String name;
    private final List<String> orderRecord;

    public OrderedWorkerPlugin(String name, List<String> orderRecord) {
      this.name = name;
      this.orderRecord = orderRecord;
    }

    @Nonnull
    @Override
    public String getName() {
      return "test.ordered." + name;
    }

    @Override
    public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
      orderRecord.add(name);
    }

    @Override
    public void configureWorker(
        @Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {}

    @Override
    public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {}

    @Override
    public void startWorker(
        @Nonnull String taskQueue,
        @Nonnull Worker worker,
        @Nonnull BiConsumer<String, Worker> next) {
      next.accept(taskQueue, worker);
    }

    @Override
    public void shutdownWorker(
        @Nonnull String taskQueue,
        @Nonnull Worker worker,
        @Nonnull BiConsumer<String, Worker> next) {
      next.accept(taskQueue, worker);
    }

    @Override
    public void startWorkerFactory(
        @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
      next.accept(factory);
    }

    @Override
    public void shutdownWorkerFactory(
        @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
      next.accept(factory);
    }

    @Override
    public void replayWorkflowExecution(
        @Nonnull Worker worker,
        @Nonnull io.temporal.common.WorkflowExecutionHistory history,
        @Nonnull ReplayCallback next)
        throws Exception {
      next.replay(worker, history);
    }
  }
}
