package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestWorkflow;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = AutoDiscoveryAndExplicitWithDuplicatesTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-and-explicit-with-duplicate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryAndExplicitWithDuplicatesTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Autowired WorkflowClient workflowClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testAutoDiscoveryAndExplicitWithDuplicate() {
    TestWorkflow testWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    assertEquals("done", testWorkflow.execute("profile"));
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.bytaskqueue\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
