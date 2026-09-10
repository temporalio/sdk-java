package io.temporal.spring.boot.autoconfigure;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.autoconfigure.byworkernameresolver.TestWorkflow;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = AutoDiscoveryByWorkerNameResolverTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-worker-name-resolver")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AutoDiscoveryByWorkerNameResolverTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Autowired WorkflowClient workflowClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testWorkerNamePropertyResolution() {
    TestWorkflow testWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    String result = testWorkflow.execute("done");
    Assertions.assertEquals("done", result);
  }

  @ComponentScan(
      excludeFilters = {
        @ComponentScan.Filter(
            pattern =
                "io\\.temporal\\.spring\\.boot\\.autoconfigure"
                    + "\\.(bytaskqueue|byworkername|workerversioning)\\..*",
            type = FilterType.REGEX)
      })
  public static class Configuration {}
}
