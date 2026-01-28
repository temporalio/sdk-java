package io.temporal.workflow.determinism;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;

public class DeterminismFailingWorkflowImpl implements TestWorkflows.TestWorkflowStringArg {

  @Override
  public void execute(String taskQueue) {
    TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
    if (!WorkflowUnsafe.isReplaying()) {
      activities.activity1(1);
    }
  }
}
