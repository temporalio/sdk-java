package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.VersioningBehavior;
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.spring.boot.autoconfigure.workerversioning.TestWorkflow;
import io.temporal.spring.boot.autoconfigure.workerversioning.TestWorkflow2;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = WorkerVersioningTest.Configuration.class)
@ActiveProfiles(profiles = "worker-versioning")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WorkerVersioningTest {
  @Autowired ConfigurableApplicationContext applicationContext;
  @Autowired WorkflowClient workflowClient;

  private static final Logger log = LoggerFactory.getLogger("worker-versioning");

  @BeforeAll
  void checkDockerServiceAndNamespace() {
    String useDocker = System.getenv("USE_DOCKER_SERVICE");
    log.info("USE_DOCKER_SERVICE123: " + useDocker);
    if (useDocker != null) {
      log.info("useDocker.equalsIgnoreCase(true): " + useDocker.equalsIgnoreCase("true"));
    }
    log.info(
        "(useDocker != null && useDocker.equalsIgnoreCase())"
            + (useDocker != null && useDocker.equalsIgnoreCase("true")));
    Assumptions.assumeTrue(
        (useDocker != null && useDocker.equalsIgnoreCase("true")),
        "Skipping tests because USE_DOCKER_SERVICE is not set");
  }

  //  @BeforeEach
  //  void setUp() {
  //    applicationContext.start();
  //  }

  @SuppressWarnings("deprecation")
  @Test
  @Timeout(value = 10)
  public void testAutoDiscovery() {
    applicationContext.start();
    log.info("testAutoDiscovery started");
    workflowClient
        .getWorkflowServiceStubs()
        .blockingStub()
        .setWorkerDeploymentCurrentVersion(
            SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                .setNamespace(workflowClient.getOptions().getNamespace())
                .setDeploymentName("dname")
                .setVersion("dname.bid")
                .build());
    //    log.info("testAutoDiscovery 1");

    TestWorkflow testWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    //    log.info("testAutoDiscovery 2");
    WorkflowExecution we1 = WorkflowClient.start(testWorkflow::execute, "hi");
    workflowClient.newUntypedWorkflowStub(we1.getWorkflowId()).getResult(String.class);
    // Should've used pinned (via default)
    WorkflowExecutionHistory hist = workflowClient.fetchHistory(we1.getWorkflowId());
    assertTrue(
        hist.getHistory().getEventsList().stream()
            .anyMatch(
                e ->
                    e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED
                        && e.getWorkflowTaskCompletedEventAttributes().getVersioningBehavior()
                            == VersioningBehavior.VERSIONING_BEHAVIOR_PINNED));

    TestWorkflow2 testWorkflow2 =
        workflowClient.newWorkflowStub(
            TestWorkflow2.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
    WorkflowExecution we2 = WorkflowClient.start(testWorkflow2::tw2, "hi2");
    workflowClient.newUntypedWorkflowStub(we2.getWorkflowId()).getResult(String.class);
    // Should've used auto-upgrade (via annotation)
    WorkflowExecutionHistory hist2 = workflowClient.fetchHistory(we2.getWorkflowId());
    assertTrue(
        hist2.getHistory().getEventsList().stream()
            .anyMatch(
                e ->
                    e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED
                        && e.getWorkflowTaskCompletedEventAttributes().getVersioningBehavior()
                            == VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE));
    log.info("testAutoDiscovery done");
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.by.*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
