package io.temporal.spring.boot.autoconfigure.gracefulshutdown;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface SlowWorkflow {

  String TASK_QUEUE = "GracefulShutdown";

  @WorkflowMethod
  void execute();
}
