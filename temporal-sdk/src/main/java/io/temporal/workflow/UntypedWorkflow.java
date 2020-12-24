package io.temporal.workflow;

public interface UntypedWorkflow {

  Object workflow(Object[] args);

  void signal(Object[] args);

  Object query(Object[] args);
}
