package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityAndQueryTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityAndQueryWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testLocalActivityAndQuery() throws ExecutionException, InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(30))
            // Large workflow task timeout to avoid workflow task heartbeating
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowWithQuery workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflowStub::execute);

    // Ensure that query doesn't see intermediate results of the local activities execution
    // as all these activities are executed in a single workflow task.
    // Query should only see the result after executing all local activities.
    List<ForkJoinTask<String>> tasks = new ArrayList<>();
    int threads = 30;
    for (int i = 0; i < threads; i++) {
      ForkJoinTask<String> task = ForkJoinPool.commonPool().submit(workflowStub::query);
      tasks.add(task);
      Thread.sleep(5);
    }
    for (int i = 0; i < threads; i++) {
      assertEquals("run4", tasks.get(i).get());
    }

    assertEquals("done", WorkflowStub.fromTyped(workflowStub).getResult(String.class));
    activitiesImpl.assertInvocations(
        "sleepActivity", "sleepActivity", "sleepActivity", "sleepActivity", "sleepActivity");
    HistoryEvent marker =
        testWorkflowRule.getHistoryEvent(
            execution.getWorkflowId(), EventType.EVENT_TYPE_MARKER_RECORDED);
    Optional<Payloads> input =
        Optional.of(marker.getMarkerRecordedEventAttributes().getDetailsMap().get("input"));
    long arg0 =
        DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(0, input, Long.class, Long.class);
    assertEquals(1000, arg0);
  }

  public static final class TestLocalActivityAndQueryWorkflow
      implements TestWorkflows.TestWorkflowWithQuery {

    String message = "initial value";

    @Override
    public String execute() {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newLocalActivityOptions().toBuilder().build());
      for (int i = 0; i < 5; i++) {
        localActivities.sleepActivity(1000, i);
        message = "run" + i;
      }
      return "done";
    }

    @Override
    public String query() {
      return message;
    }
  }
}
