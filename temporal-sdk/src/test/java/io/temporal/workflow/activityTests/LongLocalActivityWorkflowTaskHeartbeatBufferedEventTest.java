package io.temporal.workflow.activityTests;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LongLocalActivityWorkflowTaskHeartbeatBufferedEventTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setTestTimeoutSeconds(20)
          .build();

  @Test
  public void testWorkflowCompletionWhileLocalActivityRunning() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(5))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
    WorkflowClient.start(workflowStub::execute, testWorkflowRule.getTaskQueue());
    testWorkflowRule.sleep(Duration.ofSeconds(1));
    workflowStub.signal();
    // wait for completion
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("foo", result);
    // force replay
    Assert.assertEquals("bar", workflowStub.getState());
    Assert.assertEquals(activitiesImpl.toString(), 0, activitiesImpl.invocations.size());
  }

  @WorkflowInterface
  public interface TestWorkflow1 {
    @WorkflowMethod
    String execute(String taskQueue);

    @SignalMethod
    void signal();

    @QueryMethod
    String getState();
  }

  public static class TestLongLocalActivityWorkflowTaskHeartbeatWorkflowImpl
      implements TestWorkflow1 {
    private CompletablePromise<Void> signaled = Workflow.newPromise();

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newLocalActivityOptions20sScheduleToClose());
      Async.function(localActivities::sleepActivity, 10 * 1000L, 123);
      signaled.get();
      return "foo";
    }

    @Override
    public void signal() {
      signaled.complete(null);
    }

    @Override
    public String getState() {
      return "bar";
    }
  }
}
