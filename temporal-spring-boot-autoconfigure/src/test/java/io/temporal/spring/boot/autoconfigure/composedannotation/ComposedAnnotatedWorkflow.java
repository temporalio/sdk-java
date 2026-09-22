package io.temporal.spring.boot.autoconfigure.composedannotation;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ComposedAnnotatedWorkflow {

  @WorkflowMethod
  String execute(String input);
}
