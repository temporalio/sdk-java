package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivities;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityWithCompletionClientTest {
  private static final CompletionClientActivitiesImpl completionClientActivitiesImpl =
      new CompletionClientActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncActivityWorkflowImpl.class)
          .setActivityImplementations(completionClientActivitiesImpl)
          .build();

  @After
  public void tearDown() throws Exception {
    completionClientActivitiesImpl.close();
  }

  @Test
  public void testAsyncActivity() {
    completionClientActivitiesImpl.completionClient =
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient();
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("activity1", completionClientActivitiesImpl.invocations.get(0));
  }

  public static class TestAsyncActivityWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {

      CompletionClientActivities completionClientActivities =
          Workflow.newActivityStub(
              CompletionClientActivities.class,
              SDKTestOptions.newActivityOptions20sScheduleToClose());

      Promise<String> a1 = Async.function(completionClientActivities::activity1, "1");
      assertEquals("1", a1.get());
      return "workflow";
    }
  }
}
