package io.temporal.workflow.activityTests.cancellation;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivities;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;

public class AbandonOnCancelActivityTest {

  private static final CompletionClientActivitiesImpl activitiesImpl =
      new CompletionClientActivitiesImpl();
  private final Signal activityStarted = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAbandonOnCancelActivity.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @AfterClass
  public static void afterClass() throws Exception {
    activitiesImpl.close();
  }

  @Test
  public void testAbandonOnCancelActivity() throws InterruptedException {
    activitiesImpl.setCompletionClient(
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient());
    activitiesImpl.setActivityWithDelayStartedCallback(activityStarted::signal);
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowExecution execution =
        WorkflowClient.start(client::execute, testWorkflowRule.getTaskQueue());
    activityStarted.waitForSignal();
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    SDKTestWorkflowRule.waitForOKQuery(stub);
    stub.cancel();
    try {
      stub.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
    activitiesImpl.assertInvocations("activityWithDelay");
    assertTrue(
        "Activity with CancellationType=ABANDON should never have a requested cancellation in history",
        testWorkflowRule
            .getHistoryEvents(
                execution.getWorkflowId(), EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED)
            .isEmpty());
  }

  public static class TestAbandonOnCancelActivity implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      CompletionClientActivities testActivities =
          Workflow.newActivityStub(
              CompletionClientActivities.class,
              ActivityOptions.newBuilder(SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue))
                  .setHeartbeatTimeout(Duration.ofSeconds(10))
                  .setCancellationType(ActivityCancellationType.ABANDON)
                  .build());
      testActivities.activityWithDelay(100000, true);
      return "foo";
    }
  }
}
