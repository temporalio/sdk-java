package io.temporal.common.metadata.testclasses;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface WorkflowInterfaceWithOneWorkflowMethod {
  @WorkflowMethod
  boolean workflowMethod();
}
