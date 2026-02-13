package io.temporal.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.VersioningBehavior;
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.spring.boot.autoconfigure.workerversioning.TestWorkflow;
import io.temporal.spring.boot.autoconfigure.workerversioning.TestWorkflow2;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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

@SpringBootTest(classes = WorkerVersioningTest.Configuration.class)
@ActiveProfiles(profiles = {"worker-versioning", "disable-start-workers"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WorkerVersioningTest {
  @Autowired ConfigurableApplicationContext applicationContext;
  @Autowired WorkflowClient workflowClient;

  @BeforeAll
  static void checkDockerService() {
    String useDocker = System.getenv("USE_DOCKER_SERVICE");
    Assumptions.assumeTrue(
        useDocker != null && useDocker.equalsIgnoreCase("true"),
        "Skipping tests because USE_DOCKER_SERVICE is not set");
  }

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @SuppressWarnings("deprecation")
  @Test
  @Timeout(value = 10)
  public void testAutoDiscovery() {
    // Manually start the worker because we disable automatic worker start, due to
    // automatic worker start running prior to the docker check, which causes namespace
    // errors when running in-mem unit tests
    WorkerFactory workerFactory = applicationContext.getBean(WorkerFactory.class);
    workerFactory.start();

    setCurrentVersionWithRetry();

    TestWorkflow testWorkflow =
        workflowClient.newWorkflowStub(
            TestWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("UnitTest").build());
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
  }

  @SuppressWarnings("deprecation")
  private void setCurrentVersionWithRetry() {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    while (true) {
      try {
        workflowClient
            .getWorkflowServiceStubs()
            .blockingStub()
            .setWorkerDeploymentCurrentVersion(
                SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                    .setNamespace(workflowClient.getOptions().getNamespace())
                    .setDeploymentName("dname")
                    .setVersion("dname.bid")
                    .build());
        return;
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() != Status.Code.NOT_FOUND
            || System.currentTimeMillis() > deadline) {
          throw e;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(ie);
        }
      }
    }
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.by.*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
