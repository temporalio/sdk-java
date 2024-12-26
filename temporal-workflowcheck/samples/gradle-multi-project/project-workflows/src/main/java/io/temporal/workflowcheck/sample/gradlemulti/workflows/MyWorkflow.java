package io.temporal.workflowcheck.sample.gradlemulti.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface MyWorkflow {
  @WorkflowMethod
  void errorAtNight();
}
