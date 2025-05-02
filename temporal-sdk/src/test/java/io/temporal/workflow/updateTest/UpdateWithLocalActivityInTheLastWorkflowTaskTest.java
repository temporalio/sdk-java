package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class UpdateWithLocalActivityInTheLastWorkflowTaskTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowWithUpdateImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  @Parameters({"true", "false"})
  public void testUpdateWithLocalActivityInTheLastWorkflowTask(Boolean waitOnLA) {
    WorkflowWithUpdate client = testWorkflowRule.newWorkflowStub(WorkflowWithUpdate.class);

    WorkflowStub.fromTyped(client).start(true);
    Thread asyncUpdate =
        new Thread(
            () -> {
              try {
                client.update(waitOnLA);
              } catch (Exception e) {
              }
            });
    asyncUpdate.start();
    assertEquals("done", client.execute(true));
    asyncUpdate.interrupt();
  }

  @WorkflowInterface
  public interface WorkflowWithUpdate {

    @WorkflowMethod
    String execute(Boolean finish);

    @UpdateMethod
    String update(Boolean waitOnLA);
  }

  public static class WorkflowWithUpdateImpl implements WorkflowWithUpdate {
    boolean finish = false;
    private final TestActivities.VariousTestActivities activities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute(Boolean wait) {
      if (wait) {
        Workflow.await(() -> finish);
      }
      return "done";
    }

    @Override
    public String update(Boolean waitOnLA) {
      if (waitOnLA) {
        Promise promise = Async.procedure(activities::sleepActivity, (long) 10, 0);
        Async.procedure(activities::sleepActivity, (long) 10000, 0);
        promise.get();
      }

      finish = true;
      activities.sleepActivity(1000, 0);
      return "update";
    }
  }
}
