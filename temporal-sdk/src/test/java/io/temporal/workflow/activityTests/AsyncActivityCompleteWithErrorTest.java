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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityCompleteWithErrorTest {
  private final AsyncActivityWithManualCompletion activities =
      new AsyncActivityWithManualCompletion();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activities)
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
                  .setHeartbeatTimeout(Duration.ofSeconds(1))
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
    private final AtomicBoolean postReturnHeartbeatSucceeded = new AtomicBoolean();
    private final AtomicBoolean postReturnTokenCanceled = new AtomicBoolean();
    private final AtomicReference<Throwable> postReturnHeartbeatFailure = new AtomicReference<>();

    @Override
    public int execute() {
      ActivityExecutionContext context = Activity.getExecutionContext();
      ManualActivityCompletionClient completionClient = context.useLocalManualCompletion();
      ForkJoinPool.commonPool().execute(() -> asyncActivityFn(context, completionClient));
      return 0;
    }

    private void asyncActivityFn(
        ActivityExecutionContext context, ManualActivityCompletionClient completionClient) {
      try {
        Thread.sleep(100);
        postReturnTokenCanceled.set(context.getCancellationToken().isCancellationRequested());
        context.heartbeat("after-local-manual-return");
        postReturnHeartbeatSucceeded.set(true);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        postReturnHeartbeatFailure.set(e);
      } catch (Throwable e) {
        postReturnHeartbeatFailure.set(e);
      }
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
    Assert.assertNull(activities.postReturnHeartbeatFailure.get());
    Assert.assertTrue(activities.postReturnHeartbeatSucceeded.get());
    Assert.assertFalse(activities.postReturnTokenCanceled.get());
  }
}
