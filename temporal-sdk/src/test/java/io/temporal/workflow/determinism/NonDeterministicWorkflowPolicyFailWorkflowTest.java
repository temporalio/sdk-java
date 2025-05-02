package io.temporal.workflow.determinism;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.NonDeterministicException;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class NonDeterministicWorkflowPolicyFailWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new TestActivitiesImpl())
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(Throwable.class)
                  .build(),
              DeterminismFailingWorkflowImpl.class)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testNonDeterministicWorkflowPolicyFailWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflowStringArg workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflowStringArg.class, options);
    WorkflowFailedException e =
        assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute(testWorkflowRule.getTaskQueue()));
    assertThat(e.getCause(), is(instanceOf(ApplicationFailure.class)));
    assertEquals(
        NonDeterministicException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
  }
}
