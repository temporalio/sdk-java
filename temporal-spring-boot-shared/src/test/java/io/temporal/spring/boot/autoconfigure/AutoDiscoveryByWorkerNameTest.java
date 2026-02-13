package io.temporal.spring.boot.autoconfigure;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestWorkflow;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = AutoDiscoveryByWorkerNameTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-worker-name")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryByWorkerNameTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Autowired WorkflowClient workflowClient;
  Endpoint endpoint;

  @BeforeEach
  void setUp() {
    applicationContext.start();
    endpoint =
        testWorkflowEnvironment.createNexusEndpoint(
            "AutoDiscoveryByWorkerNameTestEndpoint", "UnitTest");
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
            TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    testWorkflow.execute("nexus");

    WorkflowStub dynamicStub =
        workflowClient.newUntypedWorkflowStub(
            "DynamicWorkflow", WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    dynamicStub.start();
    Assertions.assertEquals("hello from dynamic workflow", dynamicStub.getResult(String.class));
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.bytaskqueue\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
