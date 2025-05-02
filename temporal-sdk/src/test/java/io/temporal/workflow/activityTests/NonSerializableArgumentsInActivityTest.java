package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertThrows;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NonSerializableArgumentsInActivityTest {

  private final NonDeserializableExceptionActivityImpl activitiesImpl =
      new NonDeserializableExceptionActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNonSerializableArgumentsInActivityWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testNonSerializableArgumentsInActivity() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals(
        "ApplicationFailure-io.temporal.common.converter.DataConverterException", result);
  }

  @ActivityInterface
  public interface NonDeserializableArgumentsActivity {
    void execute(int arg);
  }

  public static class TestNonSerializableArgumentsInActivityWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      StringBuilder result = new StringBuilder();
      ActivityStub activity =
          Workflow.newUntypedActivityStub(
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      ActivityStub localActivity =
          Workflow.newUntypedLocalActivityStub(
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());

      ActivityFailure activityFailure =
          assertThrows(ActivityFailure.class, () -> activity.execute("Execute", Void.class, "boo"));
      result.append(activityFailure.getCause().getClass().getSimpleName());
      result.append("-");
      activityFailure =
          assertThrows(
              ActivityFailure.class, () -> localActivity.execute("Execute", Void.class, "boo"));
      result.append(((ApplicationFailure) activityFailure.getCause()).getType());
      return result.toString();
    }
  }

  public static class NonDeserializableExceptionActivityImpl
      implements NonDeserializableArgumentsActivity {

    @Override
    public void execute(int arg) {}
  }
}
