package io.temporal.worker;

import static org.junit.Assert.assertNull;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.MDC;

public class MdcClearedBetweenTasksTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new SetMdcActivityImpl(), new GetMdcActivityImpl())
          .build();

  @Test
  public void testMdcClearedBetweenActivities() {
    TestWorkflows.TestWorkflowReturnString workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    String result = workflow.execute();
    assertNull(result);
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {
    private final SetMdcActivity setMdcActivity =
        Workflow.newActivityStub(
            SetMdcActivity.class, SDKTestOptions.newActivityOptions20sScheduleToClose());
    private final GetMdcActivity getMdcActivity =
        Workflow.newActivityStub(
            GetMdcActivity.class, SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public String execute() {
      setMdcActivity.execute();
      return getMdcActivity.execute();
    }
  }

  @ActivityInterface
  public interface SetMdcActivity {
    @ActivityMethod(name = "setMdc")
    void execute();
  }

  public static class SetMdcActivityImpl implements SetMdcActivity {
    @Override
    public void execute() {
      MDC.put("mdcTest", "value");
    }
  }

  @ActivityInterface
  public interface GetMdcActivity {
    @ActivityMethod(name = "getMdc")
    String execute();
  }

  public static class GetMdcActivityImpl implements GetMdcActivity {
    @Override
    public String execute() {
      return MDC.get("mdcTest");
    }
  }
}
