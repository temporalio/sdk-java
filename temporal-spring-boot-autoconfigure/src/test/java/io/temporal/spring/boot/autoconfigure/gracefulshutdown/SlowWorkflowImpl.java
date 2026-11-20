package io.temporal.spring.boot.autoconfigure.gracefulshutdown;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

@WorkflowImpl(taskQueues = SlowWorkflow.TASK_QUEUE)
public class SlowWorkflowImpl implements SlowWorkflow {

  private final SlowActivity activity =
      Workflow.newActivityStub(
          SlowActivity.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

  @Override
  public void execute() {
    activity.run();
  }
}
