package io.temporal.testserver.functional.timeskipping;

import static org.junit.Assume.assumeFalse;

import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * An abandoned child that closes after its parent must not disable time skipping. The test server
 * locks time skipping when a child closes until the parent processes the notification, so the lock
 * has to be released when the parent is already closed. If it leaks, the final sleep runs in real
 * time and the test times out.
 */
public class AbandonedChildTimeSkippingTest {
  private static final Duration CHILD_SLEEP = Duration.ofHours(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ParentWorkflowImpl.class, ChildWorkflowImpl.class)
          .build();

  @Before
  public void checkRealServer() {
    assumeFalse("Time skipping is a test server feature", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void timeSkippingWorksAfterAbandonedChildCompletes() {
    String childId = startParentWithAbandonedChild(ChildOutcome.COMPLETE);
    WorkflowStub child = testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(childId);
    child.getResult(Void.class);
    assertTimeSkippingWorks();
  }

  @Test
  public void timeSkippingWorksAfterAbandonedChildFails() {
    String childId = startParentWithAbandonedChild(ChildOutcome.FAIL);
    WorkflowStub child = testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(childId);
    awaitFailure(child);
    assertTimeSkippingWorks();
  }

  @Test
  public void timeSkippingWorksAfterAbandonedChildIsCanceled() {
    String childId = startParentWithAbandonedChild(ChildOutcome.CANCEL);
    WorkflowStub child = testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(childId);
    child.cancel();
    awaitFailure(child);
    assertTimeSkippingWorks();
  }

  @Test
  public void timeSkippingWorksAfterAbandonedChildTimesOut() {
    String childId = startParentWithAbandonedChild(ChildOutcome.TIME_OUT);
    WorkflowStub child = testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(childId);
    awaitFailure(child);
    assertTimeSkippingWorks();
  }

  private String startParentWithAbandonedChild(ChildOutcome outcome) {
    String childId = UUID.randomUUID().toString();
    ParentWorkflow parent =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                ParentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    // The parent returns as soon as the child has started.
    parent.execute(childId, outcome);
    return childId;
  }

  private static void awaitFailure(WorkflowStub child) {
    try {
      child.getResult(Void.class);
    } catch (WorkflowFailedException expected) {
      return;
    }
    throw new AssertionError("Child workflow was expected to fail");
  }

  private void assertTimeSkippingWorks() {
    // Blocks in real time, exceeding the test timeout, if time skipping is locked.
    testWorkflowRule.getTestEnvironment().sleep(Duration.ofDays(1));
  }

  public enum ChildOutcome {
    COMPLETE,
    FAIL,
    CANCEL,
    TIME_OUT
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    void execute(String childId, ChildOutcome outcome);
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    void execute(ChildOutcome outcome);
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public void execute(String childId, ChildOutcome outcome) {
      ChildWorkflowOptions.Builder options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowId(childId)
              .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON);
      if (outcome == ChildOutcome.TIME_OUT) {
        options.setWorkflowRunTimeout(CHILD_SLEEP.dividedBy(2));
      }
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class, options.build());
      Async.procedure(child::execute, outcome);
      Workflow.getWorkflowExecution(child).get();
    }
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public void execute(ChildOutcome outcome) {
      Workflow.sleep(CHILD_SLEEP);
      if (outcome == ChildOutcome.FAIL) {
        throw ApplicationFailure.newFailure("Child failed", "ChildFailure");
      }
    }
  }
}
