package io.temporal.spring.boot.autoconfigure.bytaskqueue;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TestWorkflow {

  @WorkflowMethod(name = "testWorkflow1")
  String execute(String input);
}
