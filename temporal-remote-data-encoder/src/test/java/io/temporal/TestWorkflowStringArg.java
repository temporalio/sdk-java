package io.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TestWorkflowStringArg {
  @WorkflowMethod
  int execute(String arg);
}
