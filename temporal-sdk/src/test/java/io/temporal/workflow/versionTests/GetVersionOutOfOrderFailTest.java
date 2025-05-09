package io.temporal.workflow.versionTests;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.NonDeterministicException;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionOutOfOrderFailTest extends BaseVersionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          // Make the workflow fail on any exception to catch NonDeterministicException
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder(getDefaultWorkflowImplementationOptions())
                  .setFailWorkflowExceptionTypes(Throwable.class)
                  .build(),
              TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionOutOfOrderFail() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException e =
        assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute(testWorkflowRule.getTaskQueue()));
    assertThat(e.getCause(), is(instanceOf(ApplicationFailure.class)));
    assertEquals(
        NonDeterministicException.class.getName(),
        ((ApplicationFailure) e.getCause().getCause().getCause()).getType());
    assertEquals(
        "[TMPRL1100] getVersion call before the existing version marker event. The most probable cause is retroactive addition of a getVersion call with an existing 'changeId'",
        ((ApplicationFailure) e.getCause().getCause().getCause()).getOriginalMessage());
  }

  @Test
  public void testGetVersionOutOfOrderFailReplay() {
    assertThrows(
        RuntimeException.class,
        () ->
            WorkflowReplayer.replayWorkflowExecutionFromResource(
                "testGetVersionOutOfOrderFail.json",
                GetVersionMultipleCallsTest.TestGetVersionWorkflowImpl.class));
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      if (WorkflowUnsafe.isReplaying()) {
        Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      }
      // Create a timer to generate a command
      Promise timer = Workflow.newTimer(Duration.ofSeconds(5));
      int version = Workflow.getVersion("changeId", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);

      timer.get();

      String result = "activity" + testActivities.activity1(1);

      return result;
    }
  }
}
