package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowStartFailureTest {
  private static final String EXISTING_WORKFLOW_ID = "duplicate-id";
  private static final Signal CHILD_EXECUTED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ParentWorkflowSpawningChildWithASpecificIdImpl.class, TestChildWorkflowImpl.class)
          .build();

  @Before
  public void setUp() throws Exception {
    TestWorkflows.NoArgsWorkflow alreadyExistingWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.NoArgsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(EXISTING_WORKFLOW_ID)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());
    WorkflowStub.fromTyped(alreadyExistingWorkflow).start();

    // wait for the workflow to start and set the signal to clear the signal for the future
    // assertion
    CHILD_EXECUTED.waitForSignal(5, TimeUnit.SECONDS);
    CHILD_EXECUTED.clearSignal();
  }

  @Test
  public void childWorkflowAlreadyExists() throws InterruptedException {
    ParentWorkflowSpawningChildWithASpecificId workflow =
        testWorkflowRule.newWorkflowStub(ParentWorkflowSpawningChildWithASpecificId.class);
    assertEquals("ok", workflow.execute());
    assertFalse(CHILD_EXECUTED.waitForSignal(1, TimeUnit.SECONDS));
  }

  @WorkflowInterface
  public interface ParentWorkflowSpawningChildWithASpecificId {
    @WorkflowMethod
    String execute();
  }

  public static class ParentWorkflowSpawningChildWithASpecificIdImpl
      implements ParentWorkflowSpawningChildWithASpecificId {
    @Override
    public String execute() {
      TestWorkflows.NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(
              TestWorkflows.NoArgsWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId(EXISTING_WORKFLOW_ID)
                  .validateAndBuildWithDefaults());
      Promise<Void> procedure = Async.procedure(child::execute);

      ChildWorkflowFailure childWorkflowFailure =
          assertThrows(
              ChildWorkflowFailure.class, () -> Workflow.getWorkflowExecution(child).get());
      Throwable cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof WorkflowExecutionAlreadyStarted);

      childWorkflowFailure = assertThrows(ChildWorkflowFailure.class, procedure::get);
      cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof WorkflowExecutionAlreadyStarted);

      return "ok";
    }
  }

  public static class TestChildWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      if (!WorkflowUnsafe.isReplaying()) {
        CHILD_EXECUTED.signal();
      }
      Workflow.sleep(Duration.ofHours(1));
    }
  }
}
