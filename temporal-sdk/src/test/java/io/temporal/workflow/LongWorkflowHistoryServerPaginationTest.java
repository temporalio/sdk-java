package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * Covers ServiceWorkflowHistoryIterator with an integration test that includes a server pagination
 */
public class LongWorkflowHistoryServerPaginationTest {
  private static final Signal ACTIVITIES_COMPLETED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongHistoryWorkflow.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .setUseTimeskipping(false)
          .build();

  @Test(timeout = 30_000)
  public void longWorkflowTriggeringServerPaginationCanFinish() throws InterruptedException {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(1))
            .setMaximumAttempts(2)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflows.TestWorkflowReturnString typedStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestWorkflowReturnString.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setRetryOptions(workflowRetryOptions)
                    .build());

    WorkflowStub untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStub.start();

    ACTIVITIES_COMPLETED.waitForSignal();
    testWorkflowRule.invalidateWorkflowCache();

    assertEquals(
        "Workflow should be able to successfully finish after a full replay that includes fetching and pagination through Server history.",
        "success",
        untypedStub.getResult(String.class));
  }

  public static class TestLongHistoryWorkflow implements TestWorkflows.TestWorkflowReturnString {

    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute() {
      List<Promise<String>> promises = new ArrayList<>();

      // Pagination is triggered after 256 batches of event and each batch may contain >1 events.
      // All events created by WFT completion commands are in the same batch with workflow task
      // completion.
      // We want to paginate at least through two pages
      for (int j = 0; j < 512; j++) {
        Promise<String> function = Async.function(activities::activity);
        promises.add(function);
      }
      Promise.allOf(promises).get();

      ACTIVITIES_COMPLETED.signal();
      // test code calls invalidate here and the next workflow task will cause replay with
      // pagination

      Workflow.sleep(Duration.ofSeconds(3));

      return "success";
    }
  }
}
