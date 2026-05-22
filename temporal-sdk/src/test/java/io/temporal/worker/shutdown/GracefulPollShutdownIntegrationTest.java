package io.temporal.worker.shutdown;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.namespace.v1.NamespaceInfo.Capabilities;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class GracefulPollShutdownIntegrationTest {

  private static final int WORKFLOW_COUNT = 10;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setDoNotStart(true)
          .setTestTimeoutSeconds(30)
          .setWorkflowTypes(LoopWorkflowImpl.class)
          .setActivityImplementations(new NoopActivityImpl())
          .build();

  @Test
  public void shutdownDuringActiveTimerActivityWorkflows() throws Exception {
    assumeTrue(
        "Requires real server with graceful poll shutdown support",
        SDKTestWorkflowRule.useExternalService);
    assumeTrue(
        "Server does not support graceful poll shutdown",
        getNamespaceCapabilities().getWorkerPollCompleteOnShutdown());

    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    List<String> workflowIds = new ArrayList<>(WORKFLOW_COUNT);
    for (int i = 0; i < WORKFLOW_COUNT; i++) {
      String workflowId = testWorkflowRule.getTaskQueue() + "-" + i;
      LoopWorkflow workflow =
          client.newWorkflowStub(
              LoopWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setWorkflowId(workflowId)
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
                  .build());
      WorkflowClient.start(workflow::run);
      workflowIds.add(workflowId);
    }

    Thread.sleep(2_000);

    long shutdownStartNanos = System.nanoTime();
    try {
      testWorkflowRule.getTestEnvironment().shutdown();
      testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
      Duration shutdownElapsed = Duration.ofNanos(System.nanoTime() - shutdownStartNanos);
      assertTrue(
          "Worker shutdown took " + shutdownElapsed + ", expected less than 5 seconds",
          shutdownElapsed.compareTo(Duration.ofSeconds(5)) < 0);
    } finally {
      for (String workflowId : workflowIds) {
        client.newUntypedWorkflowStub(workflowId).terminate("test cleanup");
      }
    }

    for (String workflowId : workflowIds) {
      WorkflowExecutionHistory history = client.fetchHistory(workflowId);
      List<HistoryEvent> badEvents =
          history.getEvents().stream()
              .filter(
                  e ->
                      e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED
                          || e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT)
              .collect(Collectors.toList());
      assertTrue(
          "Workflow "
              + workflowId
              + " had unexpected workflow task failures/timeouts: "
              + badEvents,
          badEvents.isEmpty());
    }
  }

  private Capabilities getNamespaceCapabilities() {
    DescribeNamespaceResponse response =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build());
    return response.getNamespaceInfo().getCapabilities();
  }

  @WorkflowInterface
  public interface LoopWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class LoopWorkflowImpl implements LoopWorkflow {

    private final NoopActivity activities =
        Workflow.newActivityStub(
            NoopActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public void run() {
      while (true) {
        Workflow.sleep(Duration.ofMillis(10));
        activities.noop();
      }
    }
  }

  @ActivityInterface
  public interface NoopActivity {
    @ActivityMethod
    void noop();
  }

  public static class NoopActivityImpl implements NoopActivity {
    @Override
    public void noop() {}
  }
}
