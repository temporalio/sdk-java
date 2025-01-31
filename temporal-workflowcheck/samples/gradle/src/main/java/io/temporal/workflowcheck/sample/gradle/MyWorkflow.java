package io.temporal.workflowcheck.sample.gradle;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface MyWorkflow {
  @WorkflowMethod
  void errorAtNight();
}
