package io.temporal.spring.boot.autoconfigure.byenable;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TestWorkflow {
  @WorkflowMethod
  String execute(String input);
}
