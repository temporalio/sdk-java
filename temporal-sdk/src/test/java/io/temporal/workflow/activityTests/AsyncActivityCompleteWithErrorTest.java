package io.temporal.workflow.activityTests;

import io.temporal.activity.*;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityCompleteWithErrorTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new AsyncActivityWithManualCompletion())
          .build();

  @WorkflowInterface
  public interface TestWorkflow {

    @WorkflowMethod
    String execute(String taskQueue);
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    @Override
    public String execute(String taskQueue) {
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToStartTimeout(Duration.ofSeconds(1))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      Promise<Integer> promise = Async.function(activity::execute);
      RuntimeException failure = promise.getFailure();
      Assert.assertNotNull(failure);
      Assert.assertTrue(failure.getCause() instanceof ApplicationFailure);
      ApplicationFailure cause = (ApplicationFailure) failure.getCause();
      Assert.assertEquals("simulated failure", cause.getOriginalMessage());
      Assert.assertEquals("some details", cause.getDetails().get(String.class));
      Assert.assertEquals("test", cause.getType());
      return "success";
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    int execute();
  }

  public static class AsyncActivityWithManualCompletion implements TestActivity {
    @Override
    public int execute() {
      ActivityExecutionContext context = Activity.getExecutionContext();
      ManualActivityCompletionClient completionClient = context.useLocalManualCompletion();
      ForkJoinPool.commonPool().execute(() -> asyncActivityFn(completionClient));
      return 0;
    }

    private void asyncActivityFn(ManualActivityCompletionClient completionClient) {
      completionClient.fail(
          ApplicationFailure.newFailure("simulated failure", "test", "some details"));
    }
  }

  @Test
  public void verifyActivityCompletionClientCompleteExceptionally() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    String result = workflow.execute(taskQueue);
    Assert.assertEquals("success", result);
  }
}
