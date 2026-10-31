package io.temporal.workflow.activityTests;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Test;

/** Replays histories after an Async stub call changes between a method reference and a lambda. */
public class AsyncStubSchedulingReplayTest {

  @Test
  public void methodReferenceHistoryReplaysWithLambda() throws Exception {
    SchedulingWorkflowImpl.useMethodReference = false;
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "asyncStubMethodReferenceFlagged.json", SchedulingWorkflowImpl.class);
  }

  @Test
  public void lambdaHistoryReplaysWithMethodReference() throws Exception {
    SchedulingWorkflowImpl.useMethodReference = true;
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "asyncStubLambdaFlagged.json", SchedulingWorkflowImpl.class);
  }

  @Test
  public void unflaggedMethodReferenceHistoryStillReplays() throws Exception {
    SchedulingWorkflowImpl.useMethodReference = true;
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "asyncStubMethodReferenceUnflagged.json", SchedulingWorkflowImpl.class);
  }

  @WorkflowInterface
  public interface SchedulingWorkflow {
    @WorkflowMethod
    String execute();
  }

  @ActivityInterface
  public interface TestActivities {
    String first();

    String second();
  }

  public static class SchedulingWorkflowImpl implements SchedulingWorkflow {
    static volatile boolean useMethodReference;

    private final TestActivities activities =
        Workflow.newActivityStub(
            TestActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build());

    @Override
    public String execute() {
      Promise<String> first =
          useMethodReference
              ? Async.function(activities::first)
              : Async.function(() -> activities.first());
      String second = activities.second();
      return first.get() + "," + second;
    }
  }
}
