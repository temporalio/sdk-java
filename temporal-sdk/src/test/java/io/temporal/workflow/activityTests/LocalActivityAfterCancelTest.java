package io.temporal.workflow.activityTests;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TemporalFailure;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityAfterCancelTest {
  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityRetry.class, BlockingWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void localActivityAfterChildWorkflowCanceled() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowClient.execute(workflowStub::execute, "sada");
    WorkflowStub.fromTyped(workflowStub).cancel();
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("sada"));
    Assert.assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED, exception.getWorkflowCloseEventType());
  }

  @Test
  public void testLocalActivityAfterChildWorkflowCanceledReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testLocalActivityAfterCancelTest.json",
        LocalActivityAfterCancelTest.TestLocalActivityRetry.class);
  }

  @WorkflowInterface
  public static class BlockingWorkflow implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      Workflow.await(() -> false);
      return "";
    }
  }

  public static class TestLocalActivityRetry implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      try {
        ChildWorkflowOptions childOptions =
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-child1")
                .setCancellationType(ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                .validateAndBuildWithDefaults();
        TestWorkflows.TestWorkflowReturnString child =
            Workflow.newChildWorkflowStub(
                TestWorkflows.TestWorkflowReturnString.class, childOptions);
        child.execute();
      } catch (TemporalFailure e) {
        if (CancellationScope.current().isCancelRequested()) {
          Workflow.newDetachedCancellationScope(
                  () -> {
                    VariousTestActivities act =
                        Workflow.newLocalActivityStub(
                            VariousTestActivities.class,
                            LocalActivityOptions.newBuilder()
                                .setStartToCloseTimeout(Duration.ofSeconds(5))
                                .validateAndBuildWithDefaults());
                    act.activity1(10);
                  })
              .run();
          throw e;
        }
      }
      return "dsadsa";
    }
  }
}
