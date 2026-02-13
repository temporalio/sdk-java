package io.temporal.spring.boot.autoconfigure;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestWorkflow;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = AutoDiscoveryByTaskQueueResolverTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-task-queue-dynamic-suffix")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryByTaskQueueResolverTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired WorkflowClient workflowClient;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Autowired WorkerFactory workerFactory;

  Endpoint endpoint;

  @BeforeEach
  void setUp() {
    applicationContext.start();
    endpoint =
        testWorkflowEnvironment.createNexusEndpoint(
            "AutoDiscoveryByTaskQueueEndpoint", "PropertyResolverTest");
  }

  @AfterEach
  void tearDown() {
    testWorkflowEnvironment.deleteNexusEndpoint(endpoint);
  }

  @Test
  @Timeout(value = 10)
  public void testAutoDiscovery() {
    TestWorkflow testWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue("PropertyResolverTest").build());
    testWorkflow.execute("nexus");
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
