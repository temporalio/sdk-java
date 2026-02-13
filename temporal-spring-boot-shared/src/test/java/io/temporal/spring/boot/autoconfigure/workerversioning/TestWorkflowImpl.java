package io.temporal.spring.boot.autoconfigure.workerversioning;

import io.temporal.common.VersioningBehavior;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.WorkflowVersioningBehavior;
import org.springframework.context.ConfigurableApplicationContext;

@WorkflowImpl(workers = "mainWorker")
public class TestWorkflowImpl implements TestWorkflow, TestWorkflow2 {

  // Test auto-wiring of the application context works, this is not indicative of a real-world use
  // case as the workflow implementation should be stateless.
  public TestWorkflowImpl(ConfigurableApplicationContext applicationContext) {}

  @Override
  public String execute(String input) {
    return input;
  }

  @Override
  @WorkflowVersioningBehavior(VersioningBehavior.AUTO_UPGRADE)
  public String tw2(String input) {
    return input;
  }
}
