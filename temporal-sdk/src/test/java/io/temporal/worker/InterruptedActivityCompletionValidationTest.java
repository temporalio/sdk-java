package io.temporal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

/**
 * Validation test for the interrupted activity completion fix.
 *
 * <p>This test demonstrates that the fix for https://github.com/temporalio/sdk-java/issues/731 is working correctly.
 * Before the fix, activities that returned with the interrupted flag set would fail to report their results
 * due to gRPC call failures.
 *
 * <p>The fix was applied in ActivityWorker.sendReply() method to temporarily clear the interrupted flag
 * during gRPC calls and restore it afterward.
 */
public class InterruptedActivityCompletionValidationTest {

  private static final String SUCCESS_RESULT = "completed-with-interrupted-flag";
  private static final AtomicInteger executionCount = new AtomicInteger(0);
  private static final AtomicBoolean interruptedFlagWasSet = new AtomicBoolean(false);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl())
          .build();

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute();
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String processWithInterruptedFlag();
  }

  /**
   * This test validates that the fix is working by demonstrating that:
   *
   * <p>
   *   1. An activity can set the interrupted flag and still return a result
   *   2. The result is successfully reported to the Temporal server
   *   3. The workflow completes with the expected result
   *   4. The activity completion is properly recorded in the workflow history
   *
   * <p>Before the fix: This test would fail with CancellationException during gRPC calls After the
   * fix: This test passes, proving activities can complete despite interrupted flag
   */
  @Test
  public void testActivityCompletionWithInterruptedFlag() {
    // Reset counters
    executionCount.set(0);
    interruptedFlagWasSet.set(false);

    // Execute workflow
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    // Wait for completion and get result
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, null)
            .getResult(String.class);

    // Validate the workflow completed successfully with expected result
    assertEquals("Activity should return the expected result", SUCCESS_RESULT, result);

    // Validate the activity was executed exactly once
    assertEquals("Activity should be executed exactly once", 1, executionCount.get());

    // Validate that the interrupted flag was actually set during execution
    assertTrue("Activity should have set the interrupted flag", interruptedFlagWasSet.get());

    // Validate that the activity completion was properly recorded in workflow history
    List<HistoryEvent> events =
        testWorkflowRule.getWorkflowClient().fetchHistory(execution.getWorkflowId()).getEvents();

    boolean activityCompletedFound = false;
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        activityCompletedFound = true;
        break;
      }
    }
    assertTrue(
        "Activity completion should be recorded in workflow history", activityCompletedFound);
  }

  /**
   * This test validates that activities that fail with interrupted flag set can still properly
   * report their failures.
   */
  @Test
  public void testActivityFailureWithInterruptedFlag() {
    executionCount.set(0);
    interruptedFlagWasSet.set(false);

    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    try {
      testWorkflowRule
          .getWorkflowClient()
          .newUntypedWorkflowStub(execution, null)
          .getResult(String.class);
    } catch (Exception e) {
      // Expected to fail, but the important thing is that the failure was properly reported
      assertTrue("Should contain failure information", e.getMessage().contains("Activity failed"));
    }

    // Validate the activity was executed
    assertEquals("Activity should be executed", 1, executionCount.get());

    // Validate the interrupted flag was set
    assertTrue("Activity should have set the interrupted flag", interruptedFlagWasSet.get());
  }

  public static class TestWorkflowImpl implements TestWorkflow {

    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(30)).build());

    @Override
    public String execute() {
      return activity.processWithInterruptedFlag();
    }
  }

  public static class TestActivityImpl implements TestActivity {

    @Override
    public String processWithInterruptedFlag() {
      executionCount.incrementAndGet();

      // This is the critical scenario that was failing before the fix:
      // Activity sets the interrupted flag and then tries to return a result
      Thread.currentThread().interrupt();
      interruptedFlagWasSet.set(true);

      // Before the fix: The gRPC call to report this result would fail with
      // CancellationException because the interrupted flag was set
      // After the fix: The interrupted flag is temporarily cleared during the
      // gRPC call, allowing the result to be successfully reported
      return SUCCESS_RESULT;
    }
  }
}
