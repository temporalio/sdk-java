package io.temporal.activity;

import io.temporal.common.RetryOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * An Activity that returns with the thread's interrupt flag raised must still have its result
 * reported to the server. gRPC's blocking stubs abort when {@code Thread.interrupted()} is set (see
 * {@code io.grpc.stub.ClientCalls#blockingUnaryCall}), so a raised flag would otherwise make the
 * completion call fail and the result would be lost.
 */
public class ActivityInterruptedFlagTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new InterruptingActivityImpl())
          .build();

  /** The Activity never noticed the flag and returned normally. */
  @Test
  public void activityReturningWithInterruptedFlagIsReportedAsCompleted() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    Assert.assertEquals("done", workflow.execute(false));
  }

  /** The Activity caught an InterruptedException, restored the flag, and returned a result. */
  @Test
  public void activityRestoringInterruptedFlagIsReportedAsCompleted() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    Assert.assertEquals("done", workflow.execute(true));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(boolean viaInterruptedException);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public String execute(boolean viaInterruptedException) {
      InterruptingActivity activity =
          Workflow.newActivityStub(
              InterruptingActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(5))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      return activity.execute(viaInterruptedException);
    }
  }

  @ActivityInterface
  public interface InterruptingActivity {
    String execute(boolean viaInterruptedException);
  }

  public static class InterruptingActivityImpl implements InterruptingActivity {
    @Override
    public String execute(boolean viaInterruptedException) {
      if (viaInterruptedException) {
        // Interrupt ourselves, observe the resulting InterruptedException, restore the flag and
        // carry on, which is the documented way for blocking code to propagate an interrupt.
        Thread.currentThread().interrupt();
        try {
          Thread.sleep(Long.MAX_VALUE);
          Assert.fail("expected an InterruptedException");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } else {
        // The Activity never noticed the flag.
        Thread.currentThread().interrupt();
      }
      return "done";
    }
  }
}
