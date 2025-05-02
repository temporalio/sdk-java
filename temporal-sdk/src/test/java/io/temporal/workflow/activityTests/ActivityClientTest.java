package io.temporal.workflow.activityTests;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityClientTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new ActivityClientTestActivitiesImpl())
          .build();

  @Test
  public void testActivityGetWorkflowClient() {
    TestWorkflow stub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    Assert.assertEquals("from activity of TestWorkflow", stub.execute(false));
  }

  @Test
  public void testLocalActivityGetWorkflowClient() {
    TestWorkflow stub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    Assert.assertEquals("from local activity of TestWorkflow", stub.execute(true));
  }

  @ActivityInterface
  public interface ActivityClientTestActivities {
    String query(String workflowId);
  }

  public static class ActivityClientTestActivitiesImpl implements ActivityClientTestActivities {
    @Override
    public String query(String workflowId) {
      String workflowType =
          Activity.getExecutionContext()
              .getWorkflowClient()
              .newUntypedWorkflowStub(workflowId)
              .describe()
              .getWorkflowType();
      if (Activity.getExecutionContext().getInfo().isLocal()) {
        return "from local activity of " + workflowType;
      } else {
        return "from activity of " + workflowType;
      }
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    String execute(boolean local);
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    private final ActivityClientTestActivities activities =
        Workflow.newActivityStub(
            ActivityClientTestActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build());

    private final ActivityClientTestActivities localActivities =
        Workflow.newLocalActivityStub(
            ActivityClientTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .build());

    @Override
    public String execute(boolean local) {
      if (local) {
        return localActivities.query(Workflow.getInfo().getWorkflowId());
      } else {
        return activities.query(Workflow.getInfo().getWorkflowId());
      }
    }
  }
}
