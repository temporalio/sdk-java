package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SideEffectTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSideEffectWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testSideEffect() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("activity1", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "currentTimeMillis",
            "sideEffect",
            "sideEffect",
            "sleep PT1S",
            "executeActivity customActivity1",
            "activity customActivity1");
  }

  public static class TestSideEffectWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      long workflowTime = Workflow.currentTimeMillis();
      long time1 = Workflow.sideEffect(long.class, () -> workflowTime);
      long time2 = Workflow.sideEffect(long.class, () -> workflowTime);
      assertEquals(time1, time2);
      Workflow.sleep(Duration.ofSeconds(1));
      String result;
      if (workflowTime == time1) {
        result = "activity" + testActivities.activity1(1);
      } else {
        result = testActivities.activity2("activity2", 2);
      }
      return result;
    }
  }
}
