package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;

public class UUIDAndRandomTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestUUIDAndRandom.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testUUIDAndRandom() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertTrue(hasReplayed);
    assertEquals("foo10", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "sideEffect",
            "sideEffect",
            "executeActivity Activity2",
            "activity Activity2");
  }

  public static class TestUUIDAndRandom implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      hasReplayed = WorkflowUnsafe.isReplaying();
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      Random rand1 = Workflow.newRandom();
      int r11 = rand1.nextInt();
      int r12 = r11 + rand1.nextInt();
      int savedInt = Workflow.sideEffect(int.class, () -> r12);
      String id = Workflow.randomUUID().toString() + "-" + Workflow.randomUUID().toString();
      String savedId = Workflow.sideEffect(String.class, () -> id);

      // Invoke activity in a blocking mode to ensure that asserts run after replay.
      String result = activities.activity2("foo", 10);

      // Assert that during replay values didn't change.
      if (hasReplayed) {
        assertEquals(savedId, id);
        assertEquals(savedInt, r12);
      }
      return result;
    }
  }
}
