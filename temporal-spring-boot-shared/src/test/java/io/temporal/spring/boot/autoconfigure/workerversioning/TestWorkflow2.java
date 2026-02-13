package io.temporal.spring.boot.autoconfigure.workerversioning;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TestWorkflow2 {

  @WorkflowMethod(name = "testWorkflow2")
  String tw2(String input);
}
